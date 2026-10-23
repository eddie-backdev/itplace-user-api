package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.itplace.userapi.ai.rag.postgres.PostgresBenefitSearchRepository;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 503})
    void vectorFallsBackOnElasticsearchRuntimeFailure(int status) throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class))).thenThrow(
                new co.elastic.clients.elasticsearch._types.ElasticsearchException("search",
                        co.elastic.clients.elasticsearch._types.ErrorResponse.of(e -> e.status(status)
                                .error(error -> error.type("index_unavailable").reason("unavailable")))));
        assertThat(benefitSearchService.queryVector(null, null, List.of(0.1f), 5)).isEmpty();
        org.mockito.Mockito.verify(benefitRepository).findAllWithPartnerAndTierBenefits();
    }

    @Test
    void vectorDoesNotMaskDatabaseHydrationFailure() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(searchResponse(mapOf(
                "benefitId", "20", "policyId", "30", "active", true, "score", 0.9)));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenThrow(new IllegalStateException("DB down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> benefitSearchService.queryVector(null, null, List.of(0.1f), 5))
                .hasMessage("DB down");
        org.mockito.Mockito.verify(benefitRepository, org.mockito.Mockito.never()).findAllWithPartnerAndTierBenefits();
    }

    @Test
    void vectorFallsBackWhenProviderBenefitIdIsNotNumeric() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(mapOf("benefitId", "not-a-number", "active", true, "score", 0.9)));

        assertThat(benefitSearchService.queryVector(null, null, List.of(0.1f), 5)).isEmpty();

        org.mockito.Mockito.verify(benefitRepository).findAllWithPartnerAndTierBenefits();
        org.mockito.Mockito.verify(benefitRepository, org.mockito.Mockito.never()).findAllByIdWithPartner(anyList());
    }

    @Test
    void vectorSkipsUnmappableProviderHitAndFallsBackToDatabase() throws IOException {
        JsonData malformedSource = org.mockito.Mockito.mock(JsonData.class);
        when(malformedSource.to(JsonNode.class)).thenThrow(new IllegalStateException("invalid source"));
        Hit<JsonData> hit = Hit.<JsonData>of(builder -> builder.index("benefit").id("bad")
                .score(0.9).source(malformedSource));
        SearchResponse<JsonData> response = SearchResponse.of(builder -> builder
                .took(1).timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(hits -> hits.hits(List.of(hit))));
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(response);

        assertThat(benefitSearchService.queryVector(null, null, List.of(0.1f), 5)).isEmpty();

        org.mockito.Mockito.verify(benefitRepository).findAllWithPartnerAndTierBenefits();
        org.mockito.Mockito.verify(benefitRepository, org.mockito.Mockito.never()).findAllByIdWithPartner(anyList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"inactive", "deleted_policy", "wrong_owner", "wrong_carrier", "deleted_tier", "wrong_grade"})
    void vectorRejectsStalePolicyAndTier(String scenario) throws IOException {
        Benefit benefit = Benefit.builder().benefitId(20L).partner(Partner.builder().partnerId(10L).build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(30L)
                .benefit(scenario.equals("wrong_owner") ? Benefit.builder().benefitId(21L).build() : benefit)
                .carrier(scenario.equals("wrong_carrier") ? Carrier.KT : Carrier.SKT)
                .active(!scenario.equals("inactive")).build();
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(searchResponse(mapOf(
                "benefitId", "20", "policyId", "30", "tierBenefitId", "40", "carrier", "SKT",
                "grade", "SKT_VIP", "active", true, "score", 0.9)));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList()))
                .thenReturn(scenario.equals("deleted_policy") ? List.of() : List.of(policy));
        if (List.of("wrong_owner", "deleted_tier", "wrong_grade").contains(scenario)) {
            when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(
                    scenario.equals("deleted_tier") ? List.of() : List.of(CarrierTierBenefit.builder()
                            .carrierTierBenefitId(40L).benefitCarrierPolicy(policy).grade(Grade.SKT_GOLD).build()));
        }
        assertThat(benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5)).isEmpty();
    }

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
    void queryVectorUsesCurrentDatabaseChannelContexts() throws IOException {
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
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(30L)
                .benefit(benefit).carrier(Carrier.SKT).active(true).description("새 설명").build();
        CarrierTierBenefit tier = CarrierTierBenefit.builder().carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy).grade(Grade.SKT_VIP)
                .context("온라인: 방문포장 30% 할인 / 오프라인: 매장 30% 할인").build();
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(policy))).thenReturn(List.of(tier));

        List<Candidate> candidates = benefitSearchService.queryVector(Carrier.SKT, Grade.SKT_VIP, List.of(0.1f), 5);

        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getContext()).isEqualTo("온라인: 방문포장 30% 할인 / 오프라인: 매장 30% 할인");
                    assertThat(candidate.getOnlineContext()).isEqualTo("방문포장 30% 할인");
                    assertThat(candidate.getOfflineContext()).isEqualTo("매장 30% 할인");
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
    void queryVector_dbFallbackDoesNotTrustLegacyAllFlagWithoutCompleteCatalogCoverage() throws IOException {
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

        assertThat(candidates).isEmpty();
    }

    @Test
    void queryVector_conditionFiltersDbFallbackByBusinessTypeWithoutInventingUseCases() throws IOException {
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
                    // The condition has requested use-case filters, but source data does not label them explicitly.
                    assertThat(candidate.getUseCases()).isEmpty();
                    assertThat(candidate.getNegativeUseCases()).isEmpty();
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
        BenefitCarrierPolicy first = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(30L)
                .benefit(vectorBenefit).carrier(Carrier.SKT).active(true).build();
        BenefitCarrierPolicy second = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(31L)
                .benefit(lexicalBenefit).carrier(Carrier.SKT).active(true).build();
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(first, second));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(
                CarrierTierBenefit.builder().carrierTierBenefitId(40L).benefitCarrierPolicy(first).grade(Grade.SKT_VIP).build(),
                CarrierTierBenefit.builder().carrierTierBenefitId(41L).benefitCarrierPolicy(second).grade(Grade.SKT_VIP).build()));

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
    void chatFallbackUsesMeaningfulTopicAndNeverReturnsArbitraryDiscountBenefits() {
        Benefit movie = benefit(20L, "영화", "극장 할인");
        Benefit cafe = benefit(21L, "카페", "커피 할인");
        BenefitCarrierPolicy moviePolicy = policy(movie, 30L, Carrier.KT);
        BenefitCarrierPolicy cafePolicy = policy(cafe, 31L, Carrier.KT);
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(movie, cafe));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(moviePolicy, cafePolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());

        var movieResult = benefitSearchService.queryChat(Carrier.KT, null, null, "영화 할인 혜택", 10, BenefitSearchCondition.none());
        var genericResult = benefitSearchService.queryChat(Carrier.KT, null, null, "할인 혜택", 10, BenefitSearchCondition.none());

        assertThat(movieResult.available()).isTrue();
        assertThat(movieResult.candidates()).extracting(Candidate::getBenefitName).containsExactly("극장 할인");
        assertThat(genericResult.available()).isTrue();
        assertThat(genericResult.candidates()).isEmpty();
    }

    @Test
    void postgresChatFallsBackToRelationalCandidatesWhenSearchIndexFails() {
        PostgresBenefitSearchRepository postgres = org.mockito.Mockito.mock(PostgresBenefitSearchRepository.class);
        when(postgres.searchHybrid(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("search relation missing"));
        benefitSearchService.setPostgresSearchRepository(postgres);
        benefitSearchService.setSearchProvider("postgres");
        Benefit movie = benefit(20L, "영화", "극장 할인");
        BenefitCarrierPolicy policy = policy(movie, 30L, Carrier.KT);
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(movie));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());

        var result = benefitSearchService.queryChat(Carrier.KT, null, null, "영화 할인", 10, BenefitSearchCondition.none());

        assertThat(result.available()).isTrue();
        assertThat(result.candidates()).extracting(Candidate::getBenefitName).containsExactly("극장 할인");
    }

    @Test
    void postgresChatReportsUnavailableWhenSearchAndRelationalFallbackBothFail() {
        PostgresBenefitSearchRepository postgres = org.mockito.Mockito.mock(PostgresBenefitSearchRepository.class);
        when(postgres.searchHybrid(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("search relation missing"));
        benefitSearchService.setPostgresSearchRepository(postgres);
        benefitSearchService.setSearchProvider("postgres");
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenThrow(new IllegalStateException("DB down"));

        var result = benefitSearchService.queryChat(Carrier.KT, null, null, "영화 할인", 10, BenefitSearchCondition.none());

        assertThat(result.available()).isFalse();
        assertThat(result.candidates()).isEmpty();
        org.mockito.Mockito.verify(benefitRepository, org.mockito.Mockito.times(1)).findAllWithPartnerAndTierBenefits();
    }

    @Test
    void postgresChatDoesNotRetryRelationalFallbackAfterNoHits() {
        PostgresBenefitSearchRepository postgres = org.mockito.Mockito.mock(PostgresBenefitSearchRepository.class);
        when(postgres.searchHybrid(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        benefitSearchService.setPostgresSearchRepository(postgres);
        benefitSearchService.setSearchProvider("postgres");
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenThrow(new IllegalStateException("DB down"));

        var result = benefitSearchService.queryChat(Carrier.KT, null, null, "영화 할인", 10, BenefitSearchCondition.none());

        assertThat(result.available()).isFalse();
        assertThat(result.candidates()).isEmpty();
        org.mockito.Mockito.verify(benefitRepository, org.mockito.Mockito.times(1)).findAllWithPartnerAndTierBenefits();
    }

    @Test
    void unknownGradeIncludesOnlyKnownGradeRowsAndRejectsUnprovenAllFlagRows() {
        Benefit benefit = benefit(20L, "영화", "극장 할인");
        BenefitCarrierPolicy policy = policy(benefit, 30L, Carrier.KT);
        List<CarrierTierBenefit> tiers = List.of(
                CarrierTierBenefit.builder().carrierTierBenefitId(41L).benefitCarrierPolicy(policy).grade(Grade.KT_GOLD).build(),
                CarrierTierBenefit.builder().carrierTierBenefitId(42L).benefitCarrierPolicy(policy).grade(null).isAll(false).build(),
                CarrierTierBenefit.builder().carrierTierBenefitId(43L).benefitCarrierPolicy(policy).grade(null).isAll(true).build());
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(tiers);

        var result = benefitSearchService.queryChat(Carrier.KT, null, null, "영화", 10, BenefitSearchCondition.none());

        assertThat(result.candidates()).extracting(Candidate::getTierBenefitId).containsExactly(41L);
        assertThat(result.candidates()).allSatisfy(candidate -> assertThat(candidate.getAllGrade()).isFalse());
    }

    @Test
    void lexicalOnlyEsResultsMustContainMeaningfulQueryTerms() throws IOException {
        Benefit cafe = benefit(20L, "카페", "커피 할인");
        BenefitCarrierPolicy policy = policy(cafe, 30L, Carrier.KT);
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(searchResponse(mapOf(
                "benefitId", "20", "policyId", "30", "active", true, "score", 0.9)));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(cafe));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(cafe));

        var result = benefitSearchService.queryChat(null, null, null, "없는브랜드 할인", 10, BenefitSearchCondition.none());

        assertThat(result.available()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void requestedGradeRejectsPolicyWithNoVerifiedTierOrAllGradeRow() {
        Benefit movie = benefit(20L, "영화", "극장 할인");
        BenefitCarrierPolicy policy = policy(movie, 30L, Carrier.KT);
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(movie));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());

        var result = benefitSearchService.queryChat(Carrier.KT, Grade.KT_GOLD, null, "영화", 10, BenefitSearchCondition.none());

        assertThat(result.available()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void sourceOfferForGradeWithoutCanonicalTierUsesPolicyAndOfferIdentity() {
        Benefit benefit = benefit(20L, "편의점", "편의점 포인트 적립");
        BenefitCarrierPolicy policy = policy(benefit, 30L, Carrier.KT);
        policy.setUsageType(UsageType.BOTH);
        policy.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("offer-white", "WHITE 결제 할인", "1,000원당 50원 할인", UsageType.BOTH,
                        List.of("KT_WHITE", "KT_GENERAL"), false, List.of("결제"), List.of(), List.of(), List.of(), List.of(), List.of())),
                OfferResolution.PARTIAL, null));
        CarrierTierBenefit general = CarrierTierBenefit.builder().carrierTierBenefitId(41L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_GENERAL).context("일반 혜택").build();
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_WHITE)).thenReturn(List.of(Grade.KT_WHITE));
        when(reference.resolveGradeAlias(Carrier.KT, "KT_WHITE")).thenReturn(null);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_WHITE)).thenReturn(true);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);
        benefitSearchService.setReferenceDataService(reference);
        benefitSearchService.setBenefitOfferMatcher(new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(general));

        var result = benefitSearchService.browseChatCandidates(Carrier.KT, Grade.KT_WHITE, "ANY", 10);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.getBenefitId()).isEqualTo(20L);
            assertThat(candidate.getPolicyId()).isEqualTo(30L);
            assertThat(candidate.getTierBenefitId()).isNull();
            assertThat(candidate.getCandidateKey()).isEqualTo("benefit:20:policy:30:offer:offer-white");
            assertThat(candidate.getOfferKey()).isEqualTo("offer-white");
            assertThat(candidate.getConditions().offers()).extracting(BenefitOffer::key).containsExactly("offer-white");
            assertThat(candidate.getContext()).doesNotContain("일반 혜택");
        });

        when(reference.gradesForProfile(Carrier.KT, Grade.KT_GENERAL)).thenReturn(List.of(Grade.KT_GENERAL));
        var coveredGrade = benefitSearchService.browseChatCandidates(Carrier.KT, Grade.KT_GENERAL, "ANY", 10);
        assertThat(coveredGrade).singleElement().satisfies(candidate -> {
            assertThat(candidate.getTierBenefitId()).isEqualTo(41L);
            assertThat(candidate.getOfferKey()).isNull();
            assertThat(candidate.getContext()).contains("일반 혜택");
        });
    }

    @Test
    void resolvesTierCoveredOfferKeysWithoutChangingNormalFallbackCandidates() {
        Benefit benefit = benefit(20L, "영화", "극장 할인");
        BenefitCarrierPolicy policy = policy(benefit, 30L, Carrier.KT);
        policy.setUsageType(UsageType.BOTH);
        policy.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("offer-general", "일반 영화 할인", "일반 등급 할인", UsageType.BOTH,
                        List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                OfferResolution.SCOPED, null));
        CarrierTierBenefit general = CarrierTierBenefit.builder().carrierTierBenefitId(41L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_GENERAL).context("일반 등급 할인").build();
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_GENERAL)).thenReturn(List.of(Grade.KT_GENERAL));
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_VIP)).thenReturn(List.of(Grade.KT_VIP));
        when(reference.resolveGradeAlias(Carrier.KT, "KT_GENERAL")).thenReturn(Grade.KT_GENERAL);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);
        benefitSearchService.setReferenceDataService(reference);
        benefitSearchService.setBenefitOfferMatcher(new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(general));
        String key = "benefit:20:policy:30:offer:offer-general";

        var normal = benefitSearchService.browseChatCandidates(Carrier.KT, Grade.KT_GENERAL, "ANY", 10);
        var resolved = benefitSearchService.resolveChatCandidates(List.of(key), Carrier.KT, Grade.KT_GENERAL);

        assertThat(normal).singleElement().satisfies(candidate -> {
            assertThat(candidate.getTierBenefitId()).isEqualTo(41L);
            assertThat(candidate.getOfferKey()).isNull();
        });
        assertThat(resolved).singleElement().satisfies(candidate -> {
            assertThat(candidate.getCandidateKey()).isEqualTo(key);
            assertThat(candidate.getOfferKey()).isEqualTo("offer-general");
            assertThat(candidate.getTierBenefitId()).isNull();
            assertThat(candidate.getConditions().offers()).extracting(BenefitOffer::key).containsExactly("offer-general");
        });
        assertThat(benefitSearchService.resolveChatCandidates(List.of(key), Carrier.KT, Grade.KT_VIP)).isEmpty();
        assertThat(benefitSearchService.resolveChatCandidates(List.of("benefit:20:policy:30:offer:expired"),
                Carrier.KT, Grade.KT_GENERAL)).isEmpty();
    }

    @Test
    void basicNeverMatchesLguDiamondOnlySourceBenefit() {
        Benefit benefit = benefit(20L, "카페", "다이아몬드 전용 할인");
        BenefitCarrierPolicy policy = policy(benefit, 30L, Carrier.LGU, UsageType.BOTH);
        CarrierTierBenefit diamond = CarrierTierBenefit.builder().carrierTierBenefitId(40L)
                .benefitCarrierPolicy(policy).grade(Grade.DIAMOND).context("다이아몬드 전용 할인").isAll(false).build();
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of(diamond));

        List<Candidate> basic = benefitSearchService.browseChatCandidates(Carrier.LGU, Grade.BASIC, "ANY", 10);
        List<Candidate> diamondResult = benefitSearchService.browseChatCandidates(Carrier.LGU, Grade.DIAMOND, "ANY", 10);

        assertThat(basic).isEmpty();
        assertThat(diamondResult).singleElement().satisfies(candidate ->
                assertThat(candidate.getGrade()).isEqualTo("DIAMOND"));
    }

    @Test
    void explicitOnlineBrowseIncludesOnlyKnownOnlineOrBothUsageTypes() {
        Benefit online = benefit(20L, "온라인", "앱 혜택");
        Benefit both = benefit(21L, "온라인", "웹 혜택");
        Benefit offline = benefit(22L, "매장", "현장 혜택");
        Benefit unknown = benefit(23L, "기타", "확인 필요 혜택");
        List<Benefit> benefits = List.of(online, both, offline, unknown);
        List<BenefitCarrierPolicy> policies = List.of(
                policy(online, 30L, Carrier.KT, UsageType.ONLINE),
                policy(both, 31L, Carrier.KT, UsageType.BOTH),
                policy(offline, 32L, Carrier.KT, UsageType.OFFLINE),
                policy(unknown, 33L, Carrier.KT, UsageType.UNKNOWN));
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(benefits);
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(policies);
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(anyList())).thenReturn(List.of());

        List<Candidate> result = benefitSearchService.browseChatCandidates(Carrier.KT, null, "ONLINE", 20);

        assertThat(result).extracting(Candidate::getUsageType).containsExactly("ONLINE", "BOTH");
    }

    private Benefit benefit(Long id, String category, String name) {
        return Benefit.builder().benefitId(id).benefitName(name).partner(Partner.builder().partnerId(id + 100)
                .partnerName(category + " 제휴처").category(category).build()).active(true).build();
    }

    private BenefitCarrierPolicy policy(Benefit benefit, Long id, Carrier carrier) {
        return BenefitCarrierPolicy.builder().benefitCarrierPolicyId(id).benefit(benefit).carrier(carrier).active(true).build();
    }

    private BenefitCarrierPolicy policy(Benefit benefit, Long id, Carrier carrier, UsageType usageType) {
        return BenefitCarrierPolicy.builder().benefitCarrierPolicyId(id).benefit(benefit).carrier(carrier)
                .usageType(usageType).active(true).build();
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
