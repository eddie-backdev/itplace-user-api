package com.itplace.userapi.ai.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.recommend.dto.Candidate;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitSearchServiceImpl implements BenefitSearchService {

    private final ElasticsearchClient esClient;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;

    public List<Candidate> queryVector(Grade grade, List<Float> userEmbedding, int CandidateSize) {
        try {
            KnnQuery knnQuery = KnnQuery.of(k -> k
                    .field("embedding")
                    .k(CandidateSize)
                    .numCandidates(100)
                    .queryVector(userEmbedding)
            );

            SearchRequest request = SearchRequest.of(s -> s
                    .index("benefit")
                    .knn(knnQuery)
                    .size(20) //default 10
            );

            SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
            List<Candidate> candidates = response.hits().hits().stream()
                    .map(hit -> {
                        JsonNode node = hit.source().to(JsonNode.class);
                        Long benefitId = node.get("benefitId").asLong();

                        // DB에서 상세 정보 조회
                        Benefit benefit = benefitRepository.findById(benefitId)
                                .orElseThrow(() -> new RuntimeException("혜택 정보가 존재하지 않습니다: " + benefitId));

                        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit));

                        // context 추출
                        String context = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                                .filter(tb -> tb.getGrade() == grade)
                                .map(tb -> tb.getContext() != null ? tb.getContext() : "")
                                .findFirst()
                                .orElse("등급별 혜택 정보 없음");

                        return Candidate.builder()
                                .benefitId(benefitId)
                                .partnerId(benefit.getPartner().getPartnerId())
                                .benefitName(textOrDefault(node, "benefitName", benefit.getBenefitName()))
                                .partnerName(textOrDefault(node, "partnerName", benefit.getPartner().getPartnerName()))
                                .category(textOrDefault(node, "category", benefit.getPartner().getCategory()))
                                .description(textOrDefault(node, "description", "설명 없음"))
                                .context(context)
                                .build();
                    })
                    .toList();

            if (!candidates.isEmpty()) {
                return candidates;
            }

            log.warn("혜택 ES 검색 결과가 비어 DB 후보로 대체합니다.");
        } catch (IOException | RuntimeException e) {
            log.warn("혜택 ES 유사도 검색 실패로 DB 후보로 대체합니다: {}", e.getMessage());
        }

        return fallbackCandidates(grade, CandidateSize);
    }

    private List<Candidate> fallbackCandidates(Grade grade, int candidateSize) {
        return benefitRepository.findAllWithPartnerAndTierBenefits().stream()
                .filter(benefit -> !Boolean.FALSE.equals(benefit.getActive()))
                .filter(benefit -> benefit.getPartner() != null)
                .limit(candidateSize)
                .map(benefit -> {
                    List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))
                            .stream()
                            .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                            .toList();

                    String description = policies.stream()
                            .map(BenefitCarrierPolicy::getDescription)
                            .filter(value -> value != null && !value.isBlank())
                            .findFirst()
                            .orElse("설명 없음");

                    String context = policies.isEmpty()
                            ? "등급별 혜택 정보 없음"
                            : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                                    .filter(tb -> tb.getGrade() == grade || Boolean.TRUE.equals(tb.getIsAll()))
                                    .map(CarrierTierBenefit::getContext)
                                    .filter(value -> value != null && !value.isBlank())
                                    .findFirst()
                                    .orElse("등급별 혜택 정보 없음");

                    return Candidate.builder()
                            .benefitId(benefit.getBenefitId())
                            .partnerId(benefit.getPartner().getPartnerId())
                            .benefitName(benefit.getBenefitName())
                            .partnerName(benefit.getPartner().getPartnerName())
                            .category(benefit.getPartner().getCategory())
                            .description(description)
                            .context(context)
                            .build();
                })
                .toList();
    }

    static String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return defaultValue == null ? "" : defaultValue;
        }
        return value.asText();
    }
}
