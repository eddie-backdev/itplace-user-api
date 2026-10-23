package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.ChannelDecision;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.dto.BenefitSourceBlock;
import com.itplace.userapi.benefit.dto.NormalizationIssue;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerBenefitListResponse {
    private Long partnerId;
    private String partnerName;
    private String category;
    private String image;
    private List<Carrier> carriers;
    private List<BenefitPreview> previews;

    public record BenefitPreview(
            Carrier carrier,
            Long benefitId,
            String benefitName,
            String benefitLimit,
            UsageType usageType,
            List<PreviewTierBenefit> tierBenefits,
            BenefitPreviewSummary summary,
            List<BenefitOffer> offers,
            OfferResolution offerResolution,
            ChannelDecision channelDecision,
            List<BenefitSourceBlock> sourceBlocks,
            List<NormalizationIssue> normalizationIssues,
            String sourceUrl,
            java.time.Instant sourceCapturedAt
    ) {
        public BenefitPreview(Carrier carrier, Long benefitId, String benefitName, String benefitLimit,
                              UsageType usageType, List<PreviewTierBenefit> tierBenefits, List<BenefitOffer> offers,
                              OfferResolution offerResolution, ChannelDecision channelDecision) {
            this(carrier, benefitId, benefitName, benefitLimit, usageType, tierBenefits, null, List.of(),
                    null, null, List.of(), List.of(), null, null);
        }

        public BenefitPreview(Carrier carrier, Long benefitId, String benefitName, String benefitLimit,
                              UsageType usageType, List<PreviewTierBenefit> tierBenefits) {
            this(carrier, benefitId, benefitName, benefitLimit, usageType, tierBenefits, null, List.of(),
                    null, null, List.of(), List.of(), null, null);
        }

        public BenefitPreview(Carrier carrier, Long benefitId, String benefitName, String benefitLimit,
                              UsageType usageType, List<PreviewTierBenefit> tierBenefits, List<BenefitOffer> offers) {
            this(carrier, benefitId, benefitName, benefitLimit, usageType, tierBenefits, null, List.of(),
                    offers == null || offers.isEmpty() ? null : OfferResolution.SCOPED, null, List.of(), List.of(), null, null);
        }

        public BenefitPreview {
            tierBenefits = tierBenefits == null ? List.of() : List.copyOf(tierBenefits);
            offers = List.of();
            sourceBlocks = sourceBlocks == null ? List.of() : List.copyOf(sourceBlocks);
            normalizationIssues = normalizationIssues == null ? List.of() : List.copyOf(normalizationIssues);
        }
    }

    public record BenefitPreviewSummary(String offerKey, String title, String condition, String limit,
                                        UsageType usage, List<String> grades, boolean allGrades, String version) {
        public BenefitPreviewSummary {
            grades = grades == null ? List.of() : List.copyOf(grades);
        }
    }

    public record PreviewTierBenefit(Grade grade, String context, Boolean isAll) {}
}
