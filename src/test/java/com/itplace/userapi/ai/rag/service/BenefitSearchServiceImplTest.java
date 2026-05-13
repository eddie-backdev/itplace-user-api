package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitSearchServiceImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

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
}
