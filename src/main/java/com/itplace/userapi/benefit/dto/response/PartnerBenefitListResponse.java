package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
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
            List<PreviewTierBenefit> tierBenefits
    ) {}

    public record PreviewTierBenefit(Grade grade, String context, Boolean isAll) {}
}
