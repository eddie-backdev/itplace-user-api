package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
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
public class PartnerBenefitDetailResponse {
    private Long partnerId;
    private String partnerName;
    private String category;
    private String image;
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
        private String description;
        private String benefitLimit;
        private String manual;
        private String url;
        private UsageType usageType;
        private List<TierBenefitInfo> tierBenefits;
        private Boolean isFavorite;
        private Long favoriteCount;
    }
}
