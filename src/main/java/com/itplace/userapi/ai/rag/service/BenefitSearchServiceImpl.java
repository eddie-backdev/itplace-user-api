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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitSearchServiceImpl implements BenefitSearchService {

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
        try {
            List<Query> filters = metadataFilters(carrier, grade, safeCondition);
            List<SearchHitSnapshot> hits = vectorSearch(userEmbedding, filters, candidateSize).stream()
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade, safeCondition))
                    .toList();
            List<Candidate> candidates = hydrateCandidates(carrier, grade, hits, "es_vector");
            candidates = filterCandidates(candidates, safeCondition);

            if (!candidates.isEmpty()) {
                return candidates;
            }

            log.warn("혜택 ES 검색 결과가 비어 DB 후보로 대체합니다. carrier={}, grade={}", carrier, grade);
        } catch (IOException e) {
            log.warn("혜택 ES 유사도 검색 실패로 DB 후보로 대체합니다. carrier={}, grade={}, reason={}",
                    carrier, grade, e.getMessage());
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
        String normalizedQuery = normalizeQuery(queryText);
        if (normalizedQuery.isBlank()) {
            return queryVector(carrier, grade, userEmbedding, candidateSize, condition);
        }

        BenefitSearchCondition safeCondition = condition == null ? BenefitSearchCondition.none() : condition;
        List<Query> filters = metadataFilters(carrier, grade, safeCondition);
        int searchSize = hybridCandidateSize(candidateSize);
        Map<String, HybridRankAccumulator> ranks = new LinkedHashMap<>();
        boolean searchSucceeded = false;

        try {
            mergeHybridHits(ranks, vectorSearch(userEmbedding, filters, searchSize), VECTOR_WEIGHT);
            searchSucceeded = true;
        } catch (IOException | RuntimeException e) {
            log.warn("혜택 RAG vector 검색 실패: carrier={}, grade={}, query={}, reason={}",
                    carrier, grade, normalizedQuery, e.getMessage());
        }

        try {
            mergeHybridHits(ranks, lexicalSearch(normalizedQuery, filters, searchSize), LEXICAL_WEIGHT);
            searchSucceeded = true;
        } catch (IOException | RuntimeException e) {
            log.warn("혜택 RAG lexical 검색 실패: carrier={}, grade={}, query={}, reason={}",
                    carrier, grade, normalizedQuery, e.getMessage());
        }

        if (searchSucceeded) {
            List<SearchHitSnapshot> hits = ranks.values().stream()
                    .sorted(Comparator
                            .comparingDouble(HybridRankAccumulator::score).reversed()
                            .thenComparing(HybridRankAccumulator::bestRank)
                            .thenComparing(HybridRankAccumulator::key))
                    .map(HybridRankAccumulator::toSnapshot)
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade, safeCondition))
                    .limit(candidateSize)
                    .toList();
            List<Candidate> candidates = filterCandidates(hydrateCandidates(carrier, grade, hits, "es_hybrid"), safeCondition);
            if (!candidates.isEmpty()) {
                return candidates;
            }
            log.warn("혜택 RAG hybrid 검색 결과가 비어 DB 후보로 대체합니다. carrier={}, grade={}, query={}",
                    carrier, grade, normalizedQuery);
        }

        return fallbackCandidates(carrier, grade, candidateSize, safeCondition);
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
            filters.add(Query.of(q -> q.bool(b -> b
                    .should(s -> s.term(t -> t.field("grade").value(grade.name())))
                    .should(s -> s.term(t -> t.field("isAllGrade").value(true)))
                    .minimumShouldMatch("1")
            )));
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

    private static boolean matchesHitMetadata(JsonNode node, Carrier carrier, Grade grade, BenefitSearchCondition condition) {
        if (!booleanOrDefault(node, "active", true)) {
            return false;
        }
        if (carrier != null && !carrier.name().equals(textOrDefault(node, "carrier", ""))) {
            return false;
        }
        boolean gradeMatches = grade == null
                || grade.name().equals(textOrDefault(node, "grade", ""))
                || booleanOrDefault(node, "isAllGrade", false);
        if (!gradeMatches) {
            return false;
        }
        String businessType = textOrDefault(node, "businessType", "");
        return (condition.requiredBusinessTypes().isEmpty() || condition.requiredBusinessTypes().contains(businessType))
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
        HydratedPolicyContext policyContext = hydratePolicyContext(carrier, grade, orderedBenefits);

        return hits.stream()
                .map(hit -> toCandidate(hit, benefits.get(hit.benefitId()), policyContext, source))
                .filter(candidate -> candidate != null)
                .toList();
    }

    private Candidate toCandidate(SearchHitSnapshot hit, Benefit benefit, HydratedPolicyContext policyContext, String source) {
        if (benefit == null || Boolean.FALSE.equals(benefit.getActive()) || benefit.getPartner() == null) {
            return null;
        }

        JsonNode node = hit.node();
        Long benefitId = hit.benefitId();
        return Candidate.builder()
                .benefitId(benefitId)
                .policyId(hit.policyId())
                .tierBenefitId(hit.tierBenefitId())
                .partnerId(benefit.getPartner().getPartnerId())
                .benefitName(textOrDefault(node, "benefitName", benefit.getBenefitName()))
                .partnerName(textOrDefault(node, "partnerName", benefit.getPartner().getPartnerName()))
                .category(textOrDefault(node, "category", benefit.getPartner().getCategory()))
                .mainCategory(textOrDefault(node, "mainCategory", ""))
                .carrier(textOrDefault(node, "carrier", ""))
                .grade(textOrDefault(node, "grade", ""))
                .allGrade(booleanOrDefault(node, "isAllGrade", false))
                .usageType(textOrDefault(node, "usageType", ""))
                .benefitType(textOrDefault(node, "benefitType", ""))
                .sourceKey(textOrDefault(node, "sourceKey", ""))
                .sourceUrl(textOrDefault(node, "sourceUrl", ""))
                .description(textOrDefault(node, "description", policyContext.descriptionFor(benefitId)))
                .context(textOrDefault(node, "tierContext", textOrDefault(node, "context", policyContext.contextFor(benefitId))))
                .onlineContext(blankToNull(textOrDefault(node, "onlineContext", policyContext.onlineContextFor(benefitId))))
                .offlineContext(blankToNull(textOrDefault(node, "offlineContext", policyContext.offlineContextFor(benefitId))))
                .businessType(textOrDefault(node, "businessType", "OTHER"))
                .useCases(textList(node, "useCases"))
                .negativeUseCases(textList(node, "negativeUseCases"))
                .tags(textList(node, "tags"))
                .candidateSource(source)
                .semanticScore(hit.score())
                .scoreComponents(Map.of(
                        "semantic_similarity", hit.score(),
                        "source_es_vector", "es_vector".equals(source) ? 1.0 : 0.0,
                        "source_es_hybrid", "es_hybrid".equals(source) ? 1.0 : 0.0,
                        "source_db_fallback", "db_fallback".equals(source) ? 1.0 : 0.0
                ))
                .build();
    }

    private HydratedPolicyContext hydratePolicyContext(Carrier carrier, Grade grade, List<Benefit> benefits) {
        if (benefits.isEmpty()) {
            return new HydratedPolicyContext(Map.of(), Map.of(), Map.of(), Map.of());
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(benefits).stream()
                .filter(policy -> matchesPolicy(policy, carrier))
                .toList();
        List<CarrierTierBenefit> tierBenefits = policies.isEmpty()
                ? List.of()
                : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                .filter(tierBenefit -> matchesTier(tierBenefit, grade))
                .toList();

        Map<Long, String> descriptions = new LinkedHashMap<>();
        for (BenefitCarrierPolicy policy : policies) {
            Long benefitId = policy.getBenefit().getBenefitId();
            if (policy.getDescription() != null && !policy.getDescription().isBlank()) {
                descriptions.putIfAbsent(benefitId, policy.getDescription());
            }
        }

        Map<Long, String> contexts = new LinkedHashMap<>();
        for (CarrierTierBenefit tierBenefit : tierBenefits) {
            BenefitCarrierPolicy policy = tierBenefit.getBenefitCarrierPolicy();
            Long benefitId = policy.getBenefit().getBenefitId();
            if (tierBenefit.getContext() != null && !tierBenefit.getContext().isBlank()) {
                contexts.putIfAbsent(benefitId, tierBenefit.getContext());
            }
        }

        Map<Long, String> onlineContexts = new LinkedHashMap<>();
        Map<Long, String> offlineContexts = new LinkedHashMap<>();
        for (CarrierTierBenefit tierBenefit : tierBenefits) {
            BenefitCarrierPolicy policy = tierBenefit.getBenefitCarrierPolicy();
            Long benefitId = policy.getBenefit().getBenefitId();
            BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(tierBenefit.getContext());
            if (splitContext.onlineContext() != null && !splitContext.onlineContext().isBlank()) {
                onlineContexts.putIfAbsent(benefitId, splitContext.onlineContext());
            }
            if (splitContext.offlineContext() != null && !splitContext.offlineContext().isBlank()) {
                offlineContexts.putIfAbsent(benefitId, splitContext.offlineContext());
            }
        }

        return new HydratedPolicyContext(descriptions, contexts, onlineContexts, offlineContexts);
    }

    private List<Candidate> fallbackCandidates(Carrier carrier, Grade grade, int candidateSize, BenefitSearchCondition condition) {
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
            List<CarrierTierBenefit> matchingTierBenefits = allTierBenefits.stream()
                    .filter(tierBenefit -> matchesTier(tierBenefit, grade))
                    .toList();
            if (allTierBenefits.isEmpty()) {
                addFallbackCandidate(candidates, benefit, policy, null, condition);
            } else if (!matchingTierBenefits.isEmpty()) {
                for (CarrierTierBenefit tierBenefit : matchingTierBenefits) {
                    addFallbackCandidate(candidates, benefit, policy, tierBenefit, condition);
                }
            }
            if (candidates.size() >= candidateSize) {
                break;
            }
        }
        return candidates.stream().limit(candidateSize).toList();
    }

    private void addFallbackCandidate(List<Candidate> candidates,
                                      Benefit benefit,
                                      BenefitCarrierPolicy policy,
                                      CarrierTierBenefit tierBenefit,
                                      BenefitSearchCondition condition) {
        Candidate candidate = toFallbackCandidate(benefit, policy, tierBenefit);
        if (candidateMatchesCondition(candidate, condition)) {
            candidates.add(candidate);
        }
    }

    private Candidate toFallbackCandidate(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tierBenefit) {
        BenefitRagMetadata metadata = metadataClassifier.classify(
                benefit.getPartner().getPartnerName(),
                benefit.getPartner().getCategory(),
                policy.getBenefit() == null || policy.getBenefit().getMainCategory() == null
                        ? "" : policy.getBenefit().getMainCategory().name(),
                benefit.getBenefitName(),
                policy.getDescription(),
                policy.getManual(),
                tierBenefit == null ? "" : tierBenefit.getContext()
        );
        BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(tierBenefit == null ? null : tierBenefit.getContext());
        return Candidate.builder()
                .benefitId(benefit.getBenefitId())
                .policyId(policy.getBenefitCarrierPolicyId())
                .tierBenefitId(tierBenefit == null ? null : tierBenefit.getCarrierTierBenefitId())
                .partnerId(benefit.getPartner().getPartnerId())
                .benefitName(benefit.getBenefitName())
                .partnerName(benefit.getPartner().getPartnerName())
                .category(benefit.getPartner().getCategory())
                .mainCategory(policy.getBenefit() == null || policy.getBenefit().getMainCategory() == null
                        ? "" : policy.getBenefit().getMainCategory().name())
                .carrier(policy.getCarrier() == null ? "" : policy.getCarrier().name())
                .grade(tierBenefit == null || tierBenefit.getGrade() == null ? "" : tierBenefit.getGrade().name())
                .allGrade(tierBenefit == null || Boolean.TRUE.equals(tierBenefit.getIsAll()))
                .usageType(policy.getUsageType() == null ? "" : policy.getUsageType().name())
                .benefitType(policy.getType() == null ? "" : policy.getType().name())
                .sourceKey(policy.getSourceKey())
                .sourceUrl(firstNonBlank(policy.getSourceUrl(), policy.getUrl()))
                .description(textOrDefault(policy.getDescription(), "설명 없음"))
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

    private static boolean matchesTier(CarrierTierBenefit tierBenefit, Grade grade) {
        return tierBenefit != null
                && (grade == null || tierBenefit.getGrade() == grade || Boolean.TRUE.equals(tierBenefit.getIsAll()));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private record HydratedPolicyContext(Map<Long, String> descriptions,
                                         Map<Long, String> contexts,
                                         Map<Long, String> onlineContexts,
                                         Map<Long, String> offlineContexts) {
        String descriptionFor(Long benefitId) {
            return descriptions.getOrDefault(benefitId, "설명 없음");
        }

        String contextFor(Long benefitId) {
            return contexts.getOrDefault(benefitId, "등급별 혜택 정보 없음");
        }

        String onlineContextFor(Long benefitId) {
            return onlineContexts.get(benefitId);
        }

        String offlineContextFor(Long benefitId) {
            return offlineContexts.get(benefitId);
        }
    }
}
