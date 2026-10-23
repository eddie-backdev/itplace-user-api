package com.itplace.userapi.ai.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadata;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.ai.rag.postgres.BenefitSearchHit;
import com.itplace.userapi.ai.rag.postgres.PostgresBenefitSearchRepository;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.benefit.support.BenefitContextSplitter;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.benefit.service.TierBenefitSemantics;
import com.itplace.userapi.benefit.service.BenefitOfferMatcher;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitSearchServiceImpl implements BenefitSearchService {

    private static final AtomicLong LAST_POSTGRES_CHAT_WARNING = new AtomicLong();
    private static final AtomicLong LAST_POSTGRES_DB_WARNING = new AtomicLong();

    private static final String BENEFIT_INDEX = "benefit";
    private static final int DEFAULT_NUM_CANDIDATES = 100;
    private static final int HYBRID_CANDIDATE_MULTIPLIER = 4;
    private static final int HYBRID_MAX_CANDIDATES = 200;
    private static final int RRF_K = 60;
    private static final double VECTOR_WEIGHT = 1.0;
    private static final double LEXICAL_WEIGHT = 1.25;
    private static final List<String> LEXICAL_FIELDS = List.of(
            "partnerName^4",
            "benefitName^3.5",
            "carrierBenefitName^3",
            "category^2",
            "description^1.4",
            "manual",
            "context^1.2",
            "tierContext^1.2",
            "onlineContext^1.3",
            "offlineContext^1.3",
            "businessType^1.2",
            "useCases^1.2",
            "tags"
    );

    private final ElasticsearchClient esClient;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final BenefitRagMetadataClassifier metadataClassifier;

    private PostgresBenefitSearchRepository postgresSearchRepository;
    private ReferenceDataService referenceDataService;
    private BenefitOfferMatcher benefitOfferMatcher;
    private String searchProvider = "elasticsearch";

    @Autowired(required = false)
    public void setPostgresSearchRepository(PostgresBenefitSearchRepository repository) {
        this.postgresSearchRepository = repository;
    }

    @Autowired
    public void setReferenceDataService(ReferenceDataService service) { this.referenceDataService = service; }

    @Autowired(required = false)
    public void setBenefitOfferMatcher(BenefitOfferMatcher matcher) { this.benefitOfferMatcher = matcher; }

    @Value("${app.search.provider:elasticsearch}")
    public void setSearchProvider(String provider) {
        this.searchProvider = provider;
    }

    public List<Candidate> queryVector(Carrier carrier, Grade grade, List<Float> userEmbedding, int candidateSize) {
        return queryVector(carrier, grade, userEmbedding, candidateSize, BenefitSearchCondition.none());
    }

    @Override
    public List<Candidate> queryVector(Carrier carrier,
                                       Grade grade,
                                       List<Float> userEmbedding,
                                       int candidateSize,
                                       BenefitSearchCondition condition) {
        BenefitSearchCondition safeCondition = condition == null ? BenefitSearchCondition.none() : condition;
        if (usePostgres()) {
            try {
                List<Candidate> found = filterCandidates(hydrateCandidates(carrier, grade,
                        snapshots(postgresSearchRepository.searchVector(userEmbedding, carrier, grade, safeCondition, candidateSize)), "pg_vector"), safeCondition);
                return found.isEmpty() ? fallbackCandidates(carrier, grade, candidateSize, safeCondition) : found;
            } catch (RuntimeException e) {
                log.warn("혜택 PostgreSQL vector 검색 실패, 관계형 DB 후보로 대체합니다. type={}", e.getClass().getSimpleName());
                return fallbackCandidates(carrier, grade, candidateSize, safeCondition);
            }
        }
        List<SearchHitSnapshot> hits;
        try {
            hits = vectorSearch(userEmbedding, metadataFilters(carrier, grade, safeCondition), candidateSize).stream()
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade, safeCondition))
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.warn("혜택 ES 유사도 검색 실패로 DB 후보로 대체합니다. carrier={}, grade={}, reason={}",
                    carrier, grade, e.getMessage());
            return fallbackCandidates(carrier, grade, candidateSize, safeCondition);
        }
        List<Candidate> candidates = filterCandidates(hydrateCandidates(carrier, grade, hits, "es_vector"), safeCondition);
        if (!candidates.isEmpty()) {
            return candidates;
        }

        return fallbackCandidates(carrier, grade, candidateSize, safeCondition);
    }

    @Override
    public List<Candidate> queryHybrid(Carrier carrier,
                                       Grade grade,
                                       List<Float> userEmbedding,
                                       String queryText,
                                       int candidateSize,
                                       BenefitSearchCondition condition) {
        String query = normalizeQuery(queryText);
        if (query.isBlank()) return queryVector(carrier, grade, userEmbedding, candidateSize, condition);
        BenefitSearchCondition safe = condition == null ? BenefitSearchCondition.none() : condition;
        if (usePostgres()) {
            try {
                List<Candidate> found = filterCandidates(hydrateCandidates(carrier, grade,
                        snapshots(postgresSearchRepository.searchHybrid(userEmbedding, query, carrier, grade, safe, candidateSize)), "pg_hybrid"), safe);
                return found.isEmpty() ? fallbackCandidates(carrier, grade, candidateSize, safe) : found;
            } catch (RuntimeException e) {
                log.warn("혜택 PostgreSQL hybrid 검색 실패, 관계형 DB 후보로 대체합니다. type={}", e.getClass().getSimpleName());
                return fallbackCandidates(carrier, grade, candidateSize, safe);
            }
        }
        List<Query> filters = metadataFilters(carrier, grade, safe);
        int size = hybridCandidateSize(candidateSize);
        Map<String, HybridRankAccumulator> ranks = new LinkedHashMap<>();
        boolean succeeded = false;
        try { mergeHybridHits(ranks, vectorSearch(userEmbedding, filters, size), VECTOR_WEIGHT); succeeded = true; }
        catch (IOException | RuntimeException e) { log.warn("혜택 RAG vector 검색 실패: carrier={}, grade={}, reason={}", carrier, grade, e.getMessage()); }
        try { mergeHybridHits(ranks, lexicalSearch(query, filters, size), LEXICAL_WEIGHT); succeeded = true; }
        catch (IOException | RuntimeException e) { log.warn("혜택 RAG lexical 검색 실패: carrier={}, grade={}, reason={}", carrier, grade, e.getMessage()); }
        if (succeeded) {
            List<SearchHitSnapshot> hits = ranks.values().stream()
                    .sorted(Comparator.comparingDouble(HybridRankAccumulator::score).reversed()
                            .thenComparing(HybridRankAccumulator::bestRank).thenComparing(HybridRankAccumulator::key))
                    .map(HybridRankAccumulator::toSnapshot)
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade, safe)).limit(candidateSize).toList();
            List<Candidate> found = filterCandidates(hydrateCandidates(carrier, grade, hits, "es_hybrid"), safe);
            if (!found.isEmpty()) return found;
        }
        // Preserve the established GET recommend fallback contract; the chat endpoint uses queryChat below.
        return fallbackCandidates(carrier, grade, candidateSize, safe);
    }

    @Override
    public ChatSearchResult queryChat(Carrier carrier, Grade grade, List<Float> userEmbedding, String queryText,
                                     int candidateSize, BenefitSearchCondition condition) {
        String normalizedQuery = normalizeQuery(queryText);
        if (normalizedQuery.isBlank()) {
            return new ChatSearchResult(List.of(), true);
        }

        BenefitSearchCondition safeCondition = condition == null ? BenefitSearchCondition.none() : condition;
        if (usePostgres()) {
            try {
                List<BenefitSearchHit> pgHits = postgresSearchRepository.searchHybrid(userEmbedding, normalizedQuery,
                        carrier, grade, safeCondition, candidateSize);
                List<Candidate> hydrated = hydrateCandidates(carrier, grade, snapshots(pgHits), "pg_hybrid");
                List<Candidate> conditioned = filterCandidates(hydrated, safeCondition);
                log.info("benefit_search stage=postgres_chat carrier={} preHydrate={} postHydrate={} postCondition={}",
                        carrier, pgHits.size(), hydrated.size(), conditioned.size());
                List<Candidate> candidates = conditioned
                        .stream().filter(candidate -> eligibleForRequestedGrade(candidate, grade)).toList();
                if (!candidates.isEmpty()) return new ChatSearchResult(candidates, true);
            } catch (RuntimeException e) {
                warnPostgresChatFailure(LAST_POSTGRES_CHAT_WARNING, "혜택 PostgreSQL chat 검색 실패. type={}", e);
            }
            try {
                return new ChatSearchResult(lexicalFallbackCandidates(carrier, grade, normalizedQuery,
                        Math.max(candidateSize, 80), safeCondition), true);
            } catch (RuntimeException databaseFailure) {
                warnPostgresChatFailure(LAST_POSTGRES_DB_WARNING, "혜택 PostgreSQL chat 관계형 fallback 실패. type={}", databaseFailure);
                return new ChatSearchResult(List.of(), false);
            }
        }
        List<Query> filters = metadataFilters(carrier, grade, safeCondition);
        int searchSize = hybridCandidateSize(candidateSize);
        Map<String, HybridRankAccumulator> ranks = new LinkedHashMap<>();
        boolean lexicalOnly = userEmbedding == null || userEmbedding.isEmpty();
        List<String> lexicalTerms = meaningfulTerms(normalizedQuery);

        if (userEmbedding != null && !userEmbedding.isEmpty()) {
            try {
                mergeHybridHits(ranks, vectorSearch(userEmbedding, filters, searchSize), VECTOR_WEIGHT);
            } catch (IOException | RuntimeException e) {
                log.warn("혜택 RAG vector 검색 실패: carrier={}, grade={}, query={}, reason={}",
                        carrier, grade, normalizedQuery, e.getMessage());
            }
        }

        if (!lexicalOnly || !lexicalTerms.isEmpty()) {
            String lexicalQuery = lexicalOnly ? String.join(" ", lexicalTerms) : normalizedQuery;
            try {
                mergeHybridHits(ranks, lexicalSearch(lexicalQuery, filters, searchSize), LEXICAL_WEIGHT);
            } catch (IOException | RuntimeException e) {
                log.warn("혜택 RAG lexical 검색 실패: carrier={}, grade={}, query={}, reason={}",
                        carrier, grade, lexicalQuery, e.getMessage());
            }
        }

        if (!ranks.isEmpty()) {
            List<SearchHitSnapshot> hits = ranks.values().stream()
                    .sorted(Comparator
                            .comparingDouble(HybridRankAccumulator::score).reversed()
                            .thenComparing(HybridRankAccumulator::bestRank)
                            .thenComparing(HybridRankAccumulator::key))
                    .map(HybridRankAccumulator::toSnapshot)
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade, safeCondition))
                    .limit(candidateSize)
                    .toList();
            List<Candidate> hydrated = hydrateCandidates(carrier, grade, hits, "es_hybrid");
            List<Candidate> candidates = filterCandidates(hydrated, safeCondition);
            log.info("benefit_search stage=elasticsearch_chat carrier={} preHydrate={} postHydrate={} postCondition={}",
                    carrier, hits.size(), hydrated.size(), candidates.size());
            candidates = candidates.stream()
                    .filter(candidate -> eligibleForRequestedGrade(candidate, grade)).toList();
            if (lexicalOnly) candidates = candidates.stream()
                    .filter(candidate -> lexicalScore(candidate, lexicalTerms) > 0).toList();
            if (!candidates.isEmpty()) {
                return new ChatSearchResult(candidates, true);
            }
            log.debug("혜택 RAG hybrid 검색 결과가 비었습니다. carrier={}, grade={}, query={}",
                    carrier, grade, normalizedQuery);
        }
        try {
            List<Candidate> lexicalFallback = lexicalFallbackCandidates(carrier, grade, normalizedQuery,
                    Math.max(candidateSize, 80), safeCondition);
            return new ChatSearchResult(lexicalFallback, true);
        } catch (RuntimeException e) {
            log.warn("혜택 chat DB fallback 실패: carrier={}, grade={}, type={}", carrier, grade, e.getClass().getSimpleName());
            return new ChatSearchResult(List.of(), false);
        }
    }

    private boolean usePostgres() {
        return "postgres".equalsIgnoreCase(searchProvider);
    }

    private static void warnPostgresChatFailure(AtomicLong lastWarning, String message, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous >= 60_000 && lastWarning.compareAndSet(previous, now))
            log.warn(message, failure.getClass().getSimpleName());
    }

    private List<SearchHitSnapshot> snapshots(List<BenefitSearchHit> hits) {
        return hits.stream().map(hit -> new SearchHitSnapshot(hit.benefitId(), hit.policyId(), hit.tierBenefitId(), hit.metadata(), hit.score())).toList();
    }

    private List<Candidate> lexicalFallbackCandidates(Carrier carrier, Grade grade, String query, int limit,
                                                      BenefitSearchCondition condition) {
        List<Candidate> all = fallbackCandidates(carrier, grade, 10_000, condition);
        List<String> terms = meaningfulTerms(query);
        if (terms.isEmpty()) return List.of();
        return all.stream().map(candidate -> Map.entry(candidate, lexicalScore(candidate, terms)))
                .filter(entry -> entry.getValue() > 0)
                .filter(entry -> eligibleForRequestedGrade(entry.getKey(), grade))
                .sorted(Map.Entry.<Candidate, Integer>comparingByValue().reversed())
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    private List<String> meaningfulTerms(String query) {
        java.util.Set<String> generic = java.util.Set.of("혜택", "멤버십", "할인", "통신사", "등급", "가능", "추천", "추천해", "찾아", "주세요", "부탁", "비교", "온라인", "오프라인", "근처", "주변", "어디", "알려", "내", "나", "의", "좀", "제휴처", "이용", "사용", "online", "offline", "benefit", "benefits", "membership", "discount", "nearby");
        return java.util.Arrays.stream(query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() > 1 && !generic.contains(token)).distinct().toList();
    }

    private boolean eligibleForRequestedGrade(Candidate candidate, Grade grade) {
        if (grade == null) return true;
        if (candidate.getOfferKey() != null && benefitOfferMatcher != null) {
            Carrier carrier = candidate.getCarrier() == null ? null : Carrier.valueOf(candidate.getCarrier());
            List<Grade> grades = carrier == null || referenceDataService == null ? List.of(grade)
                    : referenceDataService.gradesForProfile(carrier, grade);
            return benefitOfferMatcher.supports(candidate.getConditions(), null, carrier, grades);
        }
        if (Boolean.TRUE.equals(candidate.getAllGrade())) return true;
        return candidate.getGrade() != null && grade.name().equals(candidate.getGrade());
    }

    @Override
    public List<Candidate> resolveChatCandidates(List<String> candidateKeys, Carrier carrier, Grade grade) {
        if (candidateKeys == null || candidateKeys.isEmpty()) return List.of();
        Map<String, Candidate> available = fallbackCandidates(carrier, grade, 10_000, BenefitSearchCondition.none(), true).stream()
                .filter(candidate -> eligibleForRequestedGrade(candidate, grade))
                .collect(Collectors.toMap(Candidate::getCandidateKey, candidate -> candidate, (a, b) -> a, LinkedHashMap::new));
        return candidateKeys.stream().map(available::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Candidate> browseChatCandidates(Carrier carrier, Grade grade, String channel, int limit) {
        String requestedChannel = channel == null ? "ANY" : channel;
        return fallbackCandidates(carrier, grade, 10_000, BenefitSearchCondition.none()).stream()
                .filter(candidate -> eligibleForRequestedGrade(candidate, grade))
                .filter(candidate -> channelMatches(candidate, requestedChannel))
                .limit(Math.max(1, limit)).toList();
    }

    private boolean channelMatches(Candidate candidate, String channel) {
        String usage = candidate.getUsageType() == null ? "" : candidate.getUsageType().toUpperCase(java.util.Locale.ROOT);
        return switch (channel) {
            case "ONLINE" -> "ONLINE".equals(usage) || "BOTH".equals(usage);
            case "OFFLINE" -> "OFFLINE".equals(usage) || "BOTH".equals(usage);
            default -> List.of("ONLINE", "OFFLINE", "BOTH").contains(usage);
        };
    }

    private int lexicalScore(Candidate candidate, List<String> terms) {
        String text = String.join(" ", textOrDefault(candidate.getPartnerName(), ""),
                textOrDefault(candidate.getBenefitName(), ""), textOrDefault(candidate.getCategory(), ""),
                textOrDefault(candidate.getDescription(), ""), textOrDefault(candidate.getContext(), ""),
                textOrDefault(candidate.getManual(), ""), textOrDefault(candidate.getSourceLimitText(), ""),
                textOrDefault(candidate.getSourceOptionLabel(), ""), textOrDefault(candidate.getBusinessType(), ""),
                candidate.getUseCases() == null ? "" : String.join(" ", candidate.getUseCases()),
                candidate.getTags() == null ? "" : String.join(" ", candidate.getTags()),
                candidate.getConditions() == null ? "" : String.join(" ", candidate.getConditions().products()),
                candidate.getConditions() == null ? "" : String.join(" ", candidate.getConditions().availability()),
                candidate.getConditions() == null ? "" : String.join(" ", candidate.getConditions().limits()),
                candidate.getConditions() == null ? "" : String.join(" ", candidate.getConditions().usage()),
                candidate.getConditions() == null ? "" : String.join(" ", candidate.getConditions().exclusions()))
                .toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (candidate.getPartnerName() != null && candidate.getPartnerName().toLowerCase(java.util.Locale.ROOT).contains(term)) score += 5;
            else if (candidate.getBenefitName() != null && candidate.getBenefitName().toLowerCase(java.util.Locale.ROOT).contains(term)) score += 4;
            else if (candidate.getCategory() != null && candidate.getCategory().toLowerCase(java.util.Locale.ROOT).contains(term)) score += 3;
            else if (text.contains(term)) score += 1;
        }
        return score;
    }


    private List<SearchHitSnapshot> vectorSearch(List<Float> userEmbedding, List<Query> filters, int candidateSize) throws IOException {
        KnnQuery knnQuery = KnnQuery.of(k -> {
            KnnQuery.Builder builder = k
                    .field("embedding")
                    .k(candidateSize)
                    .numCandidates(Math.max(DEFAULT_NUM_CANDIDATES, candidateSize))
                    .queryVector(userEmbedding);
            if (!filters.isEmpty()) {
                builder.filter(filters);
            }
            return builder;
        });

        SearchRequest request = SearchRequest.of(s -> s
                .index(BENEFIT_INDEX)
                .knn(knnQuery)
                .size(candidateSize)
        );
        return execute(request);
    }

    private List<SearchHitSnapshot> lexicalSearch(String queryText, List<Query> filters, int candidateSize) throws IOException {
        SearchRequest request = SearchRequest.of(s -> s
                .index(BENEFIT_INDEX)
                .query(q -> q.bool(b -> {
                    filters.forEach(b::filter);
                    return b.must(m -> m.multiMatch(mm -> mm
                            .query(queryText)
                            .fields(LEXICAL_FIELDS)
                            .fuzziness("AUTO")
                    ));
                }))
                .size(candidateSize)
        );
        return execute(request);
    }

    private List<SearchHitSnapshot> execute(SearchRequest request) throws IOException {
        SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
        return response.hits().hits().stream()
                .map(BenefitSearchServiceImpl::toSnapshot)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<Query> metadataFilters(Carrier carrier, Grade grade, BenefitSearchCondition condition) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("active").value(true))));
        if (carrier != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("carrier").value(carrier.name()))));
        }
        if (grade != null) {
            List<String> grades = eligibleGradeCodes(carrier, grade);
            filters.add(Query.of(q -> q.bool(b -> {
                grades.forEach(code -> b.should(s -> s.term(t -> t.field("grade").value(code))));
                return b.minimumShouldMatch("1");
            })));
        }
        if (!condition.requiredBusinessTypes().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                condition.requiredBusinessTypes().forEach(type ->
                        b.should(s -> s.term(t -> t.field("businessType").value(type))));
                return b.minimumShouldMatch("1");
            })));
        }
        if (!condition.excludedBusinessTypes().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                condition.excludedBusinessTypes().forEach(type ->
                        b.mustNot(s -> s.term(t -> t.field("businessType").value(type))));
                return b;
            })));
        }
        if (!condition.requiredPartnerNames().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                condition.requiredPartnerNames().forEach(name -> b.should(s -> s.matchPhrase(m -> m.field("partnerName").query(name))));
                return b.minimumShouldMatch("1");
            })));
        }
        if (!condition.excludedPartnerNames().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                condition.excludedPartnerNames().forEach(name -> b.mustNot(s -> s.matchPhrase(m -> m.field("partnerName").query(name))));
                return b;
            })));
        }
        return filters;
    }

    private static Optional<SearchHitSnapshot> toSnapshot(Hit<JsonData> hit) {
        try {
            JsonNode node = hit.source().to(JsonNode.class);
            Long benefitId = longOrNull(node, "benefitId");
            if (benefitId == null) {
                log.warn("혜택 ES hit에 benefitId가 없어 제외합니다. id={}", hit.id());
                return Optional.empty();
            }
            return Optional.of(new SearchHitSnapshot(
                    benefitId,
                    longOrNull(node, "policyId"),
                    longOrNull(node, "tierBenefitId"),
                    node,
                    hit.score() == null ? 0.0 : hit.score()
            ));
        } catch (RuntimeException e) {
            log.warn("혜택 ES hit 파싱 실패로 해당 hit만 제외합니다. id={}, reason={}", hit.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean matchesHitMetadata(JsonNode node, Carrier carrier, Grade grade, BenefitSearchCondition condition) {
        if (!booleanOrDefault(node, "active", true)) {
            return false;
        }
        if (carrier != null && !carrier.name().equals(textOrDefault(node, "carrier", ""))) {
            return false;
        }
        boolean gradeMatches = grade == null || eligibleGradeCodes(carrier, grade).contains(textOrDefault(node, "grade", ""));
        if (!gradeMatches) {
            return false;
        }
        String businessType = textOrDefault(node, "businessType", "");
        String partnerName = textOrDefault(node, "partnerName", "");
        return condition.matchesPartnerName(partnerName)
                && (condition.requiredBusinessTypes().isEmpty() || condition.requiredBusinessTypes().contains(businessType))
                && (condition.excludedBusinessTypes().isEmpty() || !condition.excludedBusinessTypes().contains(businessType))
                && !intersects(textList(node, "negativeUseCases"), condition.preferredUseCases())
                && !intersects(textList(node, "useCases"), condition.excludedUseCases());
    }

    private List<Candidate> hydrateCandidates(Carrier carrier, Grade grade, List<SearchHitSnapshot> hits, String source) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<Long> benefitIds = hits.stream()
                .map(SearchHitSnapshot::benefitId)
                .distinct()
                .toList();
        Map<Long, Benefit> benefits = benefitRepository.findAllByIdWithPartner(benefitIds).stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, benefit -> benefit));
        List<Benefit> orderedBenefits = benefitIds.stream()
                .map(benefits::get)
                .filter(benefit -> benefit != null && !Boolean.FALSE.equals(benefit.getActive()))
                .toList();
        List<BenefitCarrierPolicy> policies = orderedBenefits.isEmpty() ? List.of()
                : benefitCarrierPolicyRepository.findAllByBenefitIn(orderedBenefits).stream()
                .filter(policy -> matchesPolicy(policy, carrier)).toList();
        Map<Long, BenefitCarrierPolicy> policyById = policies.stream().collect(Collectors.toMap(
                BenefitCarrierPolicy::getBenefitCarrierPolicyId, policy -> policy));
        Map<Long, List<CarrierTierBenefit>> tiersByPolicy = policies.isEmpty() ? Map.of()
                : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
        Map<Long, java.util.Set<String>> allGradeContextsByPolicy = new java.util.HashMap<>();
        policies.forEach(policy -> {
            List<CarrierTierBenefit> tiers = tiersByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of());
            allGradeContextsByPolicy.put(policy.getBenefitCarrierPolicyId(), TierBenefitSemantics.allGradeContexts(
                    tiers, referenceDataService == null ? List.of() : referenceDataService.allGradeMembers(policy.getCarrier())));
        });
        List<Candidate> candidates = new ArrayList<>();
        for (SearchHitSnapshot hit : hits) {
            Benefit benefit = benefits.get(hit.benefitId());
            BenefitCarrierPolicy policy = hit.policyId() == null ? null : policyById.get(hit.policyId());
            if (benefit == null || Boolean.FALSE.equals(benefit.getActive()) || benefit.getPartner() == null
                    || policy == null || !benefit.getBenefitId().equals(policy.getBenefit().getBenefitId())) {
                continue;
            }
            List<CarrierTierBenefit> tiers = tiersByPolicy.getOrDefault(hit.policyId(), List.of());
            String offerKey = offerKey(hit.node());
            if (offerKey != null) {
                BenefitOffer offer = policy.getConditions() == null ? null : policy.getConditions().offers().stream()
                        .filter(item -> offerKey.equals(item.key())).findFirst().orElse(null);
                if (offer == null || (grade != null && (benefitOfferMatcher == null
                        || !benefitOfferMatcher.supports(policy.getConditions().withOffers(List.of(offer)), null,
                        policy.getCarrier(), referenceDataService == null ? List.of(grade)
                                : referenceDataService.gradesForProfile(policy.getCarrier(), grade))))) continue;
                Candidate candidate = toOfferFallbackCandidate(benefit, policy, offer,
                        textOrDefault(hit.node(), "grade", ""));
                candidate.setCandidateSource(source);
                candidate.setSemanticScore(hit.score());
                candidate.setScoreComponents(Map.of("semantic_similarity", hit.score(), "source_" + source, 1.0));
                candidates.add(candidate);
                continue;
            }
            CarrierTierBenefit tier = tiers.stream()
                    .filter(item -> java.util.Objects.equals(item.getCarrierTierBenefitId(), hit.tierBenefitId()))
                    .filter(item -> matchesTier(item, grade, policy.getCarrier())).findFirst().orElse(null);
            if (tier == null && (hit.tierBenefitId() != null || !tiers.isEmpty())) {
                continue;
            }
            Candidate candidate = toFallbackCandidate(benefit, policy, tier,
                    allGradeContextsByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), java.util.Set.of()));
            candidate.setCandidateSource(source);
            candidate.setSemanticScore(hit.score());
            candidate.setScoreComponents(Map.of("semantic_similarity", hit.score(), "source_" + source, 1.0));
            candidates.add(candidate);
        }
        return candidates;
    }

    private List<Candidate> fallbackCandidates(Carrier carrier, Grade grade, int candidateSize, BenefitSearchCondition condition) {
        return fallbackCandidates(carrier, grade, candidateSize, condition, false);
    }

    private List<Candidate> fallbackCandidates(Carrier carrier, Grade grade, int candidateSize, BenefitSearchCondition condition,
                                              boolean includeTierCoveredOffers) {
        List<Benefit> benefits = benefitRepository.findAllWithPartnerAndTierBenefits().stream()
                .filter(benefit -> !Boolean.FALSE.equals(benefit.getActive()))
                .filter(benefit -> benefit.getPartner() != null)
                .toList();
        if (benefits.isEmpty()) {
            return List.of();
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(benefits).stream()
                .filter(policy -> matchesPolicy(policy, carrier))
                .toList();
        Map<Long, Benefit> benefitsById = benefits.stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, benefit -> benefit));
        Map<Long, List<CarrierTierBenefit>> allTiersByPolicyId = policies.isEmpty()
                ? Map.of()
                : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                .collect(Collectors.groupingBy(
                        tierBenefit -> tierBenefit.getBenefitCarrierPolicy().getBenefitCarrierPolicyId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Candidate> candidates = new ArrayList<>();
        for (BenefitCarrierPolicy policy : policies) {
            Benefit benefit = benefitsById.get(policy.getBenefit().getBenefitId());
            if (benefit == null) {
                continue;
            }
            List<CarrierTierBenefit> allTierBenefits = allTiersByPolicyId.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of());
            java.util.Set<String> allGradeContexts = TierBenefitSemantics.allGradeContexts(allTierBenefits,
                    referenceDataService == null ? List.of() : referenceDataService.allGradeMembers(policy.getCarrier()));
            List<CarrierTierBenefit> matchingTierBenefits = allTierBenefits.stream()
                    .filter(tierBenefit -> matchesTier(tierBenefit, grade, policy.getCarrier()))
                    .toList();
            if (allTierBenefits.isEmpty()
                    && (benefitOfferMatcher == null || !benefitOfferMatcher.hasKnownOffers(policy.getConditions()))) {
                addFallbackCandidate(candidates, benefit, policy, null, java.util.Set.of(), condition);
            } else if (!matchingTierBenefits.isEmpty()) {
                for (CarrierTierBenefit tierBenefit : matchingTierBenefits) {
                    addFallbackCandidate(candidates, benefit, policy, tierBenefit, allGradeContexts, condition);
                }
            }
            addOfferFallbackCandidates(candidates, benefit, policy, allTierBenefits, grade, condition, includeTierCoveredOffers);
            if (candidates.size() >= candidateSize) {
                break;
            }
        }
        return candidates.stream().limit(candidateSize).toList();
    }

    private void addOfferFallbackCandidates(List<Candidate> candidates, Benefit benefit, BenefitCarrierPolicy policy,
                                            List<CarrierTierBenefit> tiers, Grade profileGrade,
                                            BenefitSearchCondition condition, boolean includeTierCoveredOffers) {
        if (benefitOfferMatcher == null || !benefitOfferMatcher.hasKnownOffers(policy.getConditions())) return;
        List<Grade> profileGrades = profileGrade == null ? List.of()
                : referenceDataService == null ? List.of(profileGrade)
                : referenceDataService.gradesForProfile(policy.getCarrier(), profileGrade);
        var allGradeContexts = TierBenefitSemantics.allGradeContexts(tiers,
                referenceDataService == null ? List.of() : referenceDataService.allGradeMembers(policy.getCarrier()));
        for (BenefitOffer offer : policy.getConditions().offers()) {
            if (profileGrade == null && !Boolean.TRUE.equals(offer.allGrades()) && offer.gradeCodes().isEmpty()) continue;
            if (profileGrade != null && !benefitOfferMatcher.supports(policy.getConditions().withOffers(List.of(offer)),
                    null, policy.getCarrier(), profileGrades)) continue;
            List<Grade> offerGrades = offer.gradeCodes().stream().map(code -> resolveOfferGrade(policy.getCarrier(), code))
                    .filter(java.util.Objects::nonNull).distinct().toList();
            List<Grade> relevantGrades = profileGrade == null ? offerGrades : offerGrades.stream()
                    .filter(profileGrades::contains).toList();
            boolean coveredByTier = Boolean.TRUE.equals(offer.allGrades())
                    ? !allGradeContexts.isEmpty()
                    : !relevantGrades.isEmpty() && relevantGrades.stream().allMatch(grade -> tiers.stream()
                    .anyMatch(tier -> grade.equals(tier.getGrade())));
            if (coveredByTier && !includeTierCoveredOffers) continue;
            if (offer.key() == null || !offer.key().matches("[A-Za-z0-9_-]{1,80}")) continue;
            Candidate candidate = toOfferFallbackCandidate(benefit, policy, offer,
                    offerGrades.size() == 1 ? offerGrades.get(0).getCode() : "");
            if (candidateMatchesCondition(candidate, condition)) candidates.add(candidate);
        }
    }

    private Candidate toOfferFallbackCandidate(Benefit benefit, BenefitCarrierPolicy policy, BenefitOffer offer,
                                               String gradeCode) {
        Candidate candidate = toFallbackCandidate(benefit, policy, null, java.util.Set.of());
        String summary = java.util.stream.Stream.of(offer.title(), offer.sourceText())
                .filter(value -> value != null && !value.isBlank()).distinct().collect(Collectors.joining(" "));
        candidate.setOfferKey(offer.key());
        candidate.setCandidateKey("benefit:%s:policy:%s:offer:%s".formatted(
                benefit.getBenefitId(), policy.getBenefitCarrierPolicyId(), offer.key()));
        candidate.setGrade(gradeCode == null ? "" : gradeCode);
        candidate.setAllGrade(Boolean.TRUE.equals(offer.allGrades()));
        candidate.setUsageType(offer.usageType() == null ? "UNKNOWN" : offer.usageType().name());
        candidate.setConditions(policy.getConditions().withOffers(List.of(offer)));
        candidate.setDescription(summary);
        candidate.setContext(summary);
        candidate.setManual(java.util.stream.Stream.of(offer.products(), offer.availability(), offer.limits(),
                offer.usage(), offer.exclusions(), com.itplace.userapi.benefit.service.BenefitFactFormatter.format(offer.facts()))
                .flatMap(List::stream).distinct().collect(Collectors.joining(" ")));
        candidate.setOnlineContext(offer.usageType() == UsageType.ONLINE || offer.usageType() == UsageType.BOTH ? summary : null);
        candidate.setOfflineContext(offer.usageType() == UsageType.OFFLINE || offer.usageType() == UsageType.BOTH ? summary : null);
        return candidate;
    }

    private String offerKey(JsonNode node) {
        if (node == null) return null;
        String key = textOrDefault(node, "offerKey", "");
        if (!key.isBlank()) return key;
        String documentId = textOrDefault(node, "documentId", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(":offer:([A-Za-z0-9_-]{1,80}):grade:").matcher(documentId);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Grade resolveOfferGrade(Carrier carrier, String code) {
        if (referenceDataService != null) {
            Grade alias = referenceDataService.resolveGradeAlias(carrier, code);
            if (alias != null && referenceDataService.isBenefitGrade(carrier, alias)) return alias;
        }
        try { return Grade.of(code); } catch (IllegalArgumentException ignored) { return null; }
    }

    private void addFallbackCandidate(List<Candidate> candidates,
                                      Benefit benefit,
                                      BenefitCarrierPolicy policy,
                                      CarrierTierBenefit tierBenefit,
                                      java.util.Set<String> allGradeContexts,
                                      BenefitSearchCondition condition) {
        Candidate candidate = toFallbackCandidate(benefit, policy, tierBenefit, allGradeContexts);
        if (candidateMatchesCondition(candidate, condition)) {
            candidates.add(candidate);
        }
    }

    private Candidate toFallbackCandidate(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tierBenefit,
                                          java.util.Set<String> allGradeContexts) {
        BenefitRagMetadata metadata = metadataClassifier.classify(
                benefit.getPartner().getPartnerName(),
                benefit.getPartner().getCategory(),
                policy.getBenefit() == null || policy.getBenefit().getMainCategory() == null
                        ? "" : policy.getBenefit().getMainCategory().name(),
                benefit.getBenefitName(),
                policy.getDescription(),
                policy.getManual(),
                tierBenefit == null ? "" : tierBenefit.getContext(),
                policy.getSourceCategory()
        );
        BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(tierBenefit == null ? null : tierBenefit.getContext());
        return Candidate.builder()
                .benefitId(benefit.getBenefitId())
                .policyId(policy.getBenefitCarrierPolicyId())
                .tierBenefitId(tierBenefit == null ? null : tierBenefit.getCarrierTierBenefitId())
                .candidateKey("benefit:%s:policy:%s:tier:%s".formatted(benefit.getBenefitId(),
                        policy.getBenefitCarrierPolicyId(), tierBenefit == null ? "policy" : tierBenefit.getCarrierTierBenefitId()))
                .partnerId(benefit.getPartner().getPartnerId())
                .benefitName(benefit.getBenefitName())
                .partnerName(benefit.getPartner().getPartnerName())
                .imgUrl(benefit.getPartner().getImage())
                .category(benefit.getPartner().getCategory())
                .mainCategory(policy.getBenefit() == null || policy.getBenefit().getMainCategory() == null
                        ? "" : policy.getBenefit().getMainCategory().name())
                .carrier(policy.getCarrier() == null ? "" : policy.getCarrier().name())
                .grade(tierBenefit == null || tierBenefit.getGrade() == null ? "" : tierBenefit.getGrade().name())
                .allGrade(tierBenefit != null && tierBenefit.getContext() != null
                        && allGradeContexts.contains(tierBenefit.getContext()))
                .usageType(policy.getUsageType() == null ? "" : policy.getUsageType().name())
                .benefitType(policy.getType() == null ? "" : policy.getType().name())
                .sourceKey(policy.getSourceKey())
                .sourceUrl(firstNonBlank(policy.getSourceUrl(), policy.getUrl()))
                .sourceCapturedAt(policy.getSourceCapturedAt())
                .sourceLimitText(policy.getSourceLimitText())
                .conditions(policy.getConditions())
                .membershipOptionType(policy.getMembershipOptionType() == null ? "UNKNOWN" : policy.getMembershipOptionType().name())
                .sourceOptionLabel(policy.getSourceOptionLabel())
                .description(textOrDefault(policy.getDescription(), "설명 없음"))
                .manual(policy.getManual())
                .context(tierBenefit == null ? "등급별 혜택 정보 없음" : textOrDefault(tierBenefit.getContext(), "등급별 혜택 정보 없음"))
                .onlineContext(splitContext.onlineContext())
                .offlineContext(splitContext.offlineContext())
                .businessType(metadata.businessType())
                .useCases(metadata.useCases())
                .negativeUseCases(metadata.negativeUseCases())
                .tags(metadata.tags())
                .candidateSource("db_fallback")
                .semanticScore(0.0)
                .scoreComponents(Map.of("source_db_fallback", 1.0))
                .build();
    }

    private List<Candidate> filterCandidates(List<Candidate> candidates, BenefitSearchCondition condition) {
        if (condition == null || condition.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> candidateMatchesCondition(candidate, condition))
                .toList();
    }

    private boolean candidateMatchesCondition(Candidate candidate, BenefitSearchCondition condition) {
        if (candidate == null || condition == null || condition.isEmpty()) {
            return candidate != null;
        }
        String businessType = candidate.getBusinessType() == null ? "" : candidate.getBusinessType();
        if (!condition.matchesPartnerName(candidate.getPartnerName())) return false;
        if (!condition.requiredBusinessTypes().isEmpty() && !condition.requiredBusinessTypes().contains(businessType)) {
            return false;
        }
        if (condition.excludedBusinessTypes().contains(businessType)) {
            return false;
        }
        if (intersects(candidate.getNegativeUseCases(), condition.preferredUseCases())) {
            return false;
        }
        return !intersects(candidate.getUseCases(), condition.excludedUseCases());
    }

    private static boolean matchesPolicy(BenefitCarrierPolicy policy, Carrier carrier) {
        return policy != null
                && !Boolean.FALSE.equals(policy.getActive())
                && (carrier == null || policy.getCarrier() == carrier);
    }

    private boolean matchesTier(CarrierTierBenefit tierBenefit, Grade grade, Carrier carrier) {
        return tierBenefit != null
                && (grade == null && tierBenefit.getGrade() != null
                    || grade != null && eligibleGradeCodes(carrier, grade).contains(tierBenefit.getGrade().getCode()));
    }

    private List<String> eligibleGradeCodes(Carrier carrier, Grade grade) {
        if (grade == null) return List.of();
        if (referenceDataService == null || carrier == null) return List.of(grade.getCode());
        List<Grade> mapped = referenceDataService.gradesForProfile(carrier, grade);
        return mapped == null ? List.of(grade.getCode()) : mapped.stream().map(Grade::getCode).toList();
    }

    static String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return defaultValue == null ? "" : defaultValue;
        }
        return value.asText();
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static boolean booleanOrDefault(JsonNode node, String fieldName, boolean defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asBoolean(defaultValue);
    }

    private static Long longOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        if (value.canConvertToLong()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> textList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
            return values;
        }
        return value.asText().isBlank() ? List.of() : List.of(value.asText());
    }

    private static boolean intersects(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.stream().anyMatch(right::contains);
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private void mergeHybridHits(Map<String, HybridRankAccumulator> ranks,
                                 List<SearchHitSnapshot> hits,
                                 double weight) {
        for (int index = 0; index < hits.size(); index++) {
            SearchHitSnapshot hit = hits.get(index);
            String key = hitKey(hit);
            int rank = index + 1;
            double rrfScore = weight / (RRF_K + rank);
            ranks.computeIfAbsent(key, ignored -> new HybridRankAccumulator(key, hit))
                    .add(rank, rrfScore, hit.score());
        }
    }

    private static String hitKey(SearchHitSnapshot hit) {
        String documentId = textOrDefault(hit.node(), "documentId", "");
        if (!documentId.isBlank()) {
            return documentId;
        }
        return String.join(":",
                String.valueOf(hit.benefitId()),
                String.valueOf(hit.policyId()),
                String.valueOf(hit.tierBenefitId())
        );
    }

    private static int hybridCandidateSize(int candidateSize) {
        int safeCandidateSize = Math.max(candidateSize, 1);
        return Math.min(Math.max(DEFAULT_NUM_CANDIDATES, safeCandidateSize * HYBRID_CANDIDATE_MULTIPLIER), HYBRID_MAX_CANDIDATES);
    }

    private static String normalizeQuery(String queryText) {
        return queryText == null ? "" : queryText.replaceAll("\\s+", " ").trim();
    }

    private record SearchHitSnapshot(Long benefitId, Long policyId, Long tierBenefitId, JsonNode node, Double score) {
    }


    private static final class HybridRankAccumulator {
        private final String key;
        private final SearchHitSnapshot representative;
        private double score;
        private int bestRank = Integer.MAX_VALUE;
        private double bestRawScore;

        private HybridRankAccumulator(String key, SearchHitSnapshot representative) {
            this.key = key;
            this.representative = representative;
        }

        private HybridRankAccumulator add(int rank, double score, double rawScore) {
            this.score += score;
            this.bestRank = Math.min(this.bestRank, rank);
            this.bestRawScore = Math.max(this.bestRawScore, rawScore);
            return this;
        }

        private String key() {
            return key;
        }

        private double score() {
            return score;
        }

        private int bestRank() {
            return bestRank;
        }

        private SearchHitSnapshot toSnapshot() {
            return new SearchHitSnapshot(
                    representative.benefitId(),
                    representative.policyId(),
                    representative.tierBenefitId(),
                    representative.node(),
                    Math.max(score, bestRawScore)
            );
        }
    }

}
