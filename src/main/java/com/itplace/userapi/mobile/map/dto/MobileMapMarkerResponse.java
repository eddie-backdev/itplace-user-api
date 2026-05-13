package com.itplace.userapi.mobile.map.dto;

import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import java.util.List;

public record MobileMapMarkerResponse(
        long storeId,
        long partnerId,
        String storeName,
        String partnerName,
        Double lat,
        Double lng,
        String logoUrl,
        String category,
        double distanceMeters,
        String benefitSummary,
        Boolean hasCoupon,
        List<TierBenefitDto> tierBenefits
) {
    private static final String DEFAULT_BENEFIT_SUMMARY = "사용 가능한 혜택을 확인해 보세요.";

    public static MobileMapMarkerResponse from(StoreDetailResponse response) {
        String benefitSummary = response.getTierBenefit() == null
                ? DEFAULT_BENEFIT_SUMMARY
                : response.getTierBenefit().stream()
                .map(TierBenefitDto::getContext)
                .filter(context -> context != null && !context.isBlank())
                .findFirst()
                .orElse(DEFAULT_BENEFIT_SUMMARY);

        return new MobileMapMarkerResponse(
                response.getStore().getStoreId(),
                response.getPartner().getPartnerId(),
                response.getStore().getStoreName(),
                response.getPartner().getPartnerName(),
                response.getStore().getLatitude(),
                response.getStore().getLongitude(),
                response.getPartner().getImage(),
                response.getPartner().getCategory(),
                response.getDistance(),
                benefitSummary,
                response.getStore().getHasCoupon(),
                response.getTierBenefit()
        );
    }
}
