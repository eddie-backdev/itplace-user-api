package com.itplace.userapi.mobile.map.dto;

import com.itplace.userapi.benefit.entity.enums.Carrier;
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
        return from(response, null);
    }

    public static MobileMapMarkerResponse from(StoreDetailResponse response, Carrier carrierFilter) {
        List<TierBenefitDto> candidateTiers = response.getTierBenefit() == null
                ? List.of()
                : response.getTierBenefit().stream()
                .filter(tier -> carrierFilter == null
                        || tier.getCarrier() == null
                        || tier.getCarrier() == carrierFilter)
                .toList();
        TierBenefitDto summaryTier = candidateTiers.stream()
                .filter(tier -> tier.getContext() != null && !tier.getContext().isBlank())
                .findFirst()
                .orElseGet(() -> candidateTiers.stream().findFirst().orElse(null));
        String benefitSummary = summaryTier == null || summaryTier.getContext() == null || summaryTier.getContext().isBlank()
                ? DEFAULT_BENEFIT_SUMMARY
                : summaryTier.getContext();
        List<TierBenefitDto> summaryTiers = summaryTier == null ? List.of() : List.of(summaryTier);

        return new MobileMapMarkerResponse(
                response.getStore().getStoreId(),
                response.getPartner().getPartnerId(),
                response.getStore().getStoreName(),
                response.getPartner().getPartnerName(),
                response.getStore().getLatitude(),
                response.getStore().getLongitude(),
                response.getPartner().getImage(),
                response.getPartner().getCategory(),
                response.getDistance() * 1000.0,
                benefitSummary,
                response.getStore().getHasCoupon(),
                summaryTiers
        );
    }
}
