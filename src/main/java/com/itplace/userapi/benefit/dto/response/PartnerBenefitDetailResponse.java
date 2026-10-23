package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import java.util.List;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerBenefitDetailResponse {
    private Long partnerId;
    private String partnerName;
    private String category;
    private String image;
    private boolean hasStores;
    private List<CarrierBenefitGroup> carrierGroups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarrierBenefitGroup {
        private Carrier carrier;
        private List<CarrierBenefit> benefits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarrierBenefit {
        private Long benefitId;
        private String benefitName;
        private String displayName;
        private MembershipOptionType membershipOptionType;
        private String sourceOptionLabel;
        private String sourceBenefitName;
        private String description;
        private String benefitLimit;
        private String manual;
        private String url;
        private String sourceUrl;
        private Instant sourceCapturedAt;
        private BenefitConditions conditions;
        private UsageType usageType;
        private List<TierBenefitInfo> tierBenefits;
        private Boolean isFavorite;
        private Long favoriteCount;
    }
}
