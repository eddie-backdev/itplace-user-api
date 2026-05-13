package com.itplace.userapi.ai.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.recommend.dto.Candidate;
import java.io.IOException;
import java.util.ArrayList;
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

    private final ElasticsearchClient esClient;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;

    public List<Candidate> queryVector(Carrier carrier, Grade grade, List<Float> userEmbedding, int candidateSize) {
        try {
            List<Query> filters = metadataFilters(carrier, grade);
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

            SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
            List<SearchHitSnapshot> hits = response.hits().hits().stream()
                    .map(BenefitSearchServiceImpl::toSnapshot)
                    .flatMap(Optional::stream)
                    .filter(hit -> matchesHitMetadata(hit.node(), carrier, grade))
                    .toList();
            List<Candidate> candidates = hydrateCandidates(carrier, grade, hits, "es_vector");

            if (!candidates.isEmpty()) {
                return candidates;
            }

            log.warn("혜택 ES 검색 결과가 비어 DB 후보로 대체합니다. carrier={}, grade={}", carrier, grade);
        } catch (IOException e) {
            log.warn("혜택 ES 유사도 검색 실패로 DB 후보로 대체합니다. carrier={}, grade={}, reason={}",
                    carrier, grade, e.getMessage());
        }

        return fallbackCandidates(carrier, grade, candidateSize);
    }

    private List<Query> metadataFilters(Carrier carrier, Grade grade) {
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

    private static boolean matchesHitMetadata(JsonNode node, Carrier carrier, Grade grade) {
        if (!booleanOrDefault(node, "active", true)) {
            return false;
        }
        if (carrier != null && !carrier.name().equals(textOrDefault(node, "carrier", ""))) {
            return false;
        }
        return grade == null
                || grade.name().equals(textOrDefault(node, "grade", ""))
                || booleanOrDefault(node, "isAllGrade", false);
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
                .candidateSource(source)
                .semanticScore(hit.score())
                .scoreComponents(Map.of(
                        "semantic_similarity", hit.score(),
                        "source_es_vector", "es_vector".equals(source) ? 1.0 : 0.0,
                        "source_db_fallback", "db_fallback".equals(source) ? 1.0 : 0.0
                ))
                .build();
    }

    private HydratedPolicyContext hydratePolicyContext(Carrier carrier, Grade grade, List<Benefit> benefits) {
        if (benefits.isEmpty()) {
            return new HydratedPolicyContext(Map.of(), Map.of());
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

        return new HydratedPolicyContext(descriptions, contexts);
    }

    private List<Candidate> fallbackCandidates(Carrier carrier, Grade grade, int candidateSize) {
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
                candidates.add(toFallbackCandidate(benefit, policy, null));
            } else if (!matchingTierBenefits.isEmpty()) {
                for (CarrierTierBenefit tierBenefit : matchingTierBenefits) {
                    candidates.add(toFallbackCandidate(benefit, policy, tierBenefit));
                }
            }
            if (candidates.size() >= candidateSize) {
                break;
            }
        }
        return candidates.stream().limit(candidateSize).toList();
    }

    private Candidate toFallbackCandidate(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tierBenefit) {
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
                .candidateSource("db_fallback")
                .semanticScore(0.0)
                .scoreComponents(Map.of("source_db_fallback", 1.0))
                .build();
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

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private record SearchHitSnapshot(Long benefitId, Long policyId, Long tierBenefitId, JsonNode node, Double score) {
    }

    private record HydratedPolicyContext(Map<Long, String> descriptions, Map<Long, String> contexts) {
        String descriptionFor(Long benefitId) {
            return descriptions.getOrDefault(benefitId, "설명 없음");
        }

        String contextFor(Long benefitId) {
            return contexts.getOrDefault(benefitId, "등급별 혜택 정보 없음");
        }
    }
}
