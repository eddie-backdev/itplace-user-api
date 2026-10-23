package com.itplace.userapi.favorite.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import java.time.Instant;
import com.itplace.userapi.favorite.dto.TierBenefitDetail;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class FavoriteDetailResponse {
    private Long benefitId;
    private String benefitName;
    private String benefitDescription;
    private String benefitLimit;
    private String partnerName;
    private String partnerImage;
    private Long partnerId;
    private List<TierBenefitDetail> tiers;
    private List<CarrierPolicyDetail> carrierPolicies;

    public record CarrierPolicyDetail(
            Carrier carrier,
            String description,
            String benefitLimit,
            String sourceUrl,
            Instant sourceCapturedAt,
            String manual,
            BenefitConditions conditions
    ) {
    }
}
