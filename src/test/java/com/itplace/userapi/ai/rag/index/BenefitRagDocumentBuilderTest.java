package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitRagDocumentBuilderTest {

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Test
    void buildPendingDocumentsIndexesCarrierPolicyAndTierMetadataSeparately() {
        Benefit benefit = Benefit.builder()
                .benefitId(100L)
                .benefitName("영화 할인")
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .partner(Partner.builder()
                        .partnerId(10L)
                        .partnerName("영화관")
                        .category("영화")
                        .build())
                .active(true)
                .build();
        BenefitCarrierPolicy sktPolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(200L)
                .benefit(benefit)
                .carrier(Carrier.SKT)
                .active(true)
                .carrierBenefitName("T멤버십 영화 할인")
                .description("영화 예매 할인")
                .manual("현장/온라인 이용")
                .usageType(UsageType.BOTH)
                .type(BenefitType.DISCOUNT)
                .sourceKey("skt-movie")
                .sourceUrl("https://example.com/skt")
                .sourceCategory("culture")
                .lastCrawledAt(LocalDateTime.of(2026, 5, 13, 1, 2))
                .build();
        CarrierTierBenefit vipTier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(300L)
                .benefitCarrierPolicy(sktPolicy)
                .grade(Grade.SKT_VIP)
                .context("VIP 연 6회 무료")
                .discountValue(100)
                .isAll(false)
                .build();

        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(sktPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(sktPolicy))).thenReturn(List.of(vipTier));

        BenefitRagDocumentBuilder builder = new BenefitRagDocumentBuilder(
                benefitCarrierPolicyRepository,
                carrierTierBenefitRepository,
                new BenefitRagMetadataClassifier()
        );

        List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments = builder.buildPendingDocuments(benefit);

        assertThat(pendingDocuments)
                .singleElement()
                .satisfies(pending -> {
                    assertThat(pending.searchableText()).contains("SKT_VIP", "영화 예매 할인", "VIP 연 6회 무료");
                    BenefitDocument document = pending.withEmbedding(List.of(0.1f, 0.2f));
                    assertThat(document.getDocumentId()).isEqualTo("benefit:100:policy:200:tier:300");
                    assertThat(document.getBenefitId()).isEqualTo("100");
                    assertThat(document.getPolicyId()).isEqualTo("200");
                    assertThat(document.getTierBenefitId()).isEqualTo("300");
                    assertThat(document.getCarrier()).isEqualTo("SKT");
                    assertThat(document.getGrade()).isEqualTo("SKT_VIP");
                    assertThat(document.getIsAllGrade()).isFalse();
                    assertThat(document.getUsageType()).isEqualTo("BOTH");
                    assertThat(document.getBenefitType()).isEqualTo("DISCOUNT");
                    assertThat(document.getTierContext()).isEqualTo("VIP 연 6회 무료");
                    assertThat(document.getEmbeddingVersion()).isEqualTo(BenefitRagDocumentBuilder.EMBEDDING_VERSION);
                    assertThat(document.getContentHash()).isNotBlank();
                    assertThat(document.getSyncStatus()).isEqualTo("ACTIVE");
                    assertThat(document.getEmbedding()).containsExactly(0.1f, 0.2f);
                    assertThat(document.getBusinessType()).isEqualTo("MOVIE_THEATER");
                    assertThat(document.getUseCases()).contains("영화", "데이트", "문화");
                    assertThat(document.getTags()).contains("MOVIE_THEATER", "영화");
                });
    }
}
