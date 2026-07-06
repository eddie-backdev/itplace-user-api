package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.recommend.dto.Candidate;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitSearchServiceImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Spy
    private BenefitRagMetadataClassifier metadataClassifier = new BenefitRagMetadataClassifier();

    @InjectMocks
    private BenefitSearchServiceImpl benefitSearchService;

    @Test
    void queryVector_fallsBackToDbCandidatesWhenElasticsearchSearchFails() throws IOException {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파트너")
                .category("카페")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("아메리카노 할인")
                .partner(partner)
                .active(true)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .benefitCarrierPolicyId(30L)
                .carrier(Carrier.LGU)
                .active(true)
                .description("커피 할인")
                .build();
        CarrierTierBenefit tierBenefit = CarrierTierBenefit.builder()
                .carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy)
                .grade(Grade.VIP)
                .context("VIP 20% 할인")
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("benefit index missing"));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(tierBenefit));

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.LGU, Grade.VIP, List.of(0.1f, 0.2f), 5);

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getBenefitId()).isEqualTo(20L);
                    assertThat(candidate.getPartnerId()).isEqualTo(10L);
                    assertThat(candidate.getPartnerName()).isEqualTo("파트너");
                    assertThat(candidate.getPolicyId()).isEqualTo(30L);
                    assertThat(candidate.getTierBenefitId()).isEqualTo(40L);
                    assertThat(candidate.getCarrier()).isEqualTo("LGU");
                    assertThat(candidate.getGrade()).isEqualTo("VIP");
                    assertThat(candidate.getDescription()).isEqualTo("커피 할인");
                    assertThat(candidate.getContext()).isEqualTo("VIP 20% 할인");
                    assertThat(candidate.getOnlineContext()).isNull();
                    assertThat(candidate.getOfflineContext()).isNull();
                });
    }

    @Test
    void queryVector_hydratesChannelContextsFromElasticsearchDocument() throws IOException {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("피자집")
                .category("피자")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("피자 할인")
                .partner(partner)
                .active(true)
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(mapOf(
                        "benefitId", "20",
                        "policyId", "30",
                        "tierBenefitId", "40",
                        "partnerName", "피자집",
                        "benefitName", "피자 할인",
                        "category", "피자",
                        "active", true,
                        "carrier", "SKT",
                        "grade", "SKT_VIP",
                        "isAllGrade", false,
                        "tierContext", "온라인: 방문포장 25% 할인 / 오프라인: 매장 25% 할인",
                        "onlineContext", "방문포장 25% 할인",
                        "offlineContext", "매장 25% 할인",
                        "score", 0.91
                )));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of());

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5);

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getContext()).isEqualTo("온라인: 방문포장 25% 할인 / 오프라인: 매장 25% 할인");
                    assertThat(candidate.getOnlineContext()).isEqualTo("방문포장 25% 할인");
                    assertThat(candidate.getOfflineContext()).isEqualTo("매장 25% 할인");
                });
    }

    @Test
    void queryVector_dbFallbackSkipsPolicyWhenRequestedGradeDoesNotMatchAnyTier() throws IOException {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파트너")
                .category("카페")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("아메리카노 할인")
                .partner(partner)
                .active(true)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .benefitCarrierPolicyId(30L)
                .carrier(Carrier.SKT)
                .active(true)
                .description("커피 할인")
                .build();
        CarrierTierBenefit basicOnlyTier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy)
                .grade(Grade.SKT_GOLD)
                .isAll(false)
                .context("BASIC 5% 할인")
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("benefit index unavailable"));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(basicOnlyTier));

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5);

        assertThat(candidates).isEmpty();
    }

    @Test
    void queryVector_dbFallbackAllowsAllGradeTierForRequestedGrade() throws IOException {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파트너")
                .category("카페")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("아메리카노 할인")
                .partner(partner)
                .active(true)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .benefitCarrierPolicyId(30L)
                .carrier(Carrier.SKT)
                .active(true)
                .description("커피 할인")
                .build();
        CarrierTierBenefit allGradeTier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy)
                .grade(Grade.SKT_GOLD)
                .isAll(true)
                .context("전 등급 5% 할인")
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("benefit index unavailable"));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(allGradeTier));

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5);

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getTierBenefitId()).isEqualTo(40L);
                    assertThat(candidate.getAllGrade()).isTrue();
                    assertThat(candidate.getContext()).isEqualTo("전 등급 5% 할인");
                });
    }

    @Test
    void queryVector_conditionFiltersDbFallbackByBusinessType() throws IOException {
        Partner kidsPartner = Partner.builder()
                .partnerId(10L)
                .partnerName("서울형 키즈카페")
                .category("키즈카페, 실내놀이터")
                .build();
        Benefit kidsBenefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("입장료 할인")
                .partner(kidsPartner)
                .active(true)
                .build();
        BenefitCarrierPolicy kidsPolicy = BenefitCarrierPolicy.builder()
                .benefit(kidsBenefit)
                .benefitCarrierPolicyId(30L)
                .carrier(Carrier.SKT)
                .active(true)
                .description("키즈카페 입장료 할인")
                .build();

        Partner cafePartner = Partner.builder()
                .partnerId(11L)
                .partnerName("카페베네")
                .category("카페")
                .build();
        Benefit cafeBenefit = Benefit.builder()
                .benefitId(21L)
                .benefitName("아메리카노 할인")
                .partner(cafePartner)
                .active(true)
                .build();
        BenefitCarrierPolicy cafePolicy = BenefitCarrierPolicy.builder()
                .benefit(cafeBenefit)
                .benefitCarrierPolicyId(31L)
                .carrier(Carrier.SKT)
                .active(true)
                .description("커피 음료 할인")
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("benefit index unavailable"));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(kidsBenefit, cafeBenefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(kidsPolicy, cafePolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());

        List<Candidate> candidates = benefitSearchService.queryVector(
                Carrier.SKT,
                null,
                List.of(0.1f),
                5,
                new BenefitSearchCondition(
                        List.of("BEVERAGE_CAFE"),
                        List.of("KIDS_PLAY", "STUDY_SPACE"),
                        List.of("음료", "커피"),
                        List.of("아이동반", "공부")
                )
        );

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getPartnerName()).isEqualTo("카페베네");
                    assertThat(candidate.getBusinessType()).isEqualTo("BEVERAGE_CAFE");
                    assertThat(candidate.getUseCases()).contains("음료", "커피");
                });
    }


    @Test
    void queryHybridMergesVectorAndLexicalHitsAndBoostsExplicitKeywordMatch() throws IOException {
        Partner vectorPartner = Partner.builder()
                .partnerId(10L)
                .partnerName("상담센터")
                .category("상담")
                .build();
        Benefit vectorBenefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("상담 할인")
                .partner(vectorPartner)
                .active(true)
                .build();
        Partner lexicalPartner = Partner.builder()
                .partnerId(11L)
                .partnerName("미스터피자")
                .category("피자")
                .build();
        Benefit lexicalBenefit = Benefit.builder()
                .benefitId(21L)
                .benefitName("피자 할인")
                .partner(lexicalPartner)
                .active(true)
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(List.of(mapOf(
                        "documentId", "benefit:20:policy:30:tier:40",
                        "benefitId", "20",
                        "policyId", "30",
                        "tierBenefitId", "40",
                        "partnerName", "상담센터",
                        "benefitName", "상담 할인",
                        "category", "상담",
                        "active", true,
                        "carrier", "SKT",
                        "grade", "SKT_VIP",
                        "isAllGrade", false,
                        "businessType", "COUNSELING",
                        "tierContext", "상담 20% 할인",
                        "score", 0.99
                ))))
                .thenReturn(searchResponse(List.of(mapOf(
                        "documentId", "benefit:21:policy:31:tier:41",
                        "benefitId", "21",
                        "policyId", "31",
                        "tierBenefitId", "41",
                        "partnerName", "미스터피자",
                        "benefitName", "피자 할인",
                        "category", "피자",
                        "active", true,
                        "carrier", "SKT",
                        "grade", "SKT_VIP",
                        "isAllGrade", false,
                        "businessType", "FOOD_RESTAURANT",
                        "tierContext", "온라인 방문포장 25% 할인",
                        "score", 0.5
                ))));
        when(benefitRepository.findAllByIdWithPartner(anyList()))
                .thenReturn(List.of(lexicalBenefit, vectorBenefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of());

        List<Candidate> candidates = benefitSearchService.queryHybrid(
                Carrier.SKT,
                Grade.SKT_VIP,
                List.of(0.1f),
                "미스터피자 온라인 할인",
                2,
                BenefitSearchCondition.none()
        );

        assertThat(candidates)
                .hasSize(2)
                .first()
                .satisfies(candidate -> {
                    assertThat(candidate.getPartnerName()).isEqualTo("미스터피자");
                    assertThat(candidate.getCandidateSource()).isEqualTo("es_hybrid");
                    assertThat(candidate.getScoreComponents()).containsEntry("source_es_hybrid", 1.0);
                });
    }

    @Test
    void queryVector_dbFallbackSplitsChannelContexts() throws IOException {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("피자집")
                .category("피자")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("피자 할인")
                .partner(partner)
                .active(true)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .benefitCarrierPolicyId(30L)
                .carrier(Carrier.SKT)
                .active(true)
                .description("피자 할인")
                .build();
        CarrierTierBenefit tierBenefit = CarrierTierBenefit.builder()
                .carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP)
                .context("온라인: 방문포장 25% 할인 / 오프라인: 매장 25% 할인")
                .build();

        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("benefit index unavailable"));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(tierBenefit));

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5);

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getOnlineContext()).isEqualTo("방문포장 25% 할인");
                    assertThat(candidate.getOfflineContext()).isEqualTo("매장 25% 할인");
                });
    }

    @Test
    void textOrDefault_returnsDefaultWhenElasticsearchFieldIsMissingOrNull() throws IOException {
        JsonNode node = objectMapper.readTree("""
                {
                  "benefitName": "혜택명",
                  "category": null
                }
                """);

        assertThat(BenefitSearchServiceImpl.textOrDefault(node, "benefitName", "기본 혜택명"))
                .isEqualTo("혜택명");
        assertThat(BenefitSearchServiceImpl.textOrDefault(node, "category", ""))
                .isEmpty();
        assertThat(BenefitSearchServiceImpl.textOrDefault(node, "description", "설명 없음"))
                .isEqualTo("설명 없음");
    }

    private SearchResponse<JsonData> searchResponse(Map<String, Object> source) {
        return searchResponse(List.of(source));
    }

    private SearchResponse<JsonData> searchResponse(List<Map<String, Object>> sources) {
        return SearchResponse.of(s -> s
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(h -> h.hits(sources.stream()
                        .map(source -> Hit.<JsonData>of(hit -> hit
                                .index("benefit")
                                .id(String.valueOf(source.get("benefitId")))
                                .score(((Number) source.get("score")).doubleValue())
                                .source(jsonData(source))))
                        .toList()))
        );
    }

    private JsonData jsonData(Map<String, Object> source) {
        return JsonData.of(source, jsonpMapper);
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
