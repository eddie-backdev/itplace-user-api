package com.itplace.userapi.map.dto.response;

import com.itplace.userapi.map.dto.PartnerDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.partner.entity.Partner;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MapStorePreviewResponse {
    private Long storeId;
    private Long partnerId;
    private String storeName;
    private String partnerName;
    private String category;
    private String image;
    private Double latitude;
    private Double longitude;
    private String address;
    private String roadName;
    private String roadAddress;
    private String postCode;
    private Boolean hasCoupon;
    private List<TierBenefitDto> tierBenefit;
    private double distance;

    public static MapStorePreviewResponse of(Store store, Partner partner, List<TierBenefitDto> tierBenefitDtos,
                                             double distance) {
        return MapStorePreviewResponse.builder()
                .storeId(store.getStoreId())
                .partnerId(partner.getPartnerId())
                .storeName(store.getStoreName())
                .partnerName(partner.getPartnerName())
                .category(partner.getCategory() != null ? partner.getCategory().trim() : null)
                .image(partner.getImage())
                .latitude(store.getLocation().getY())
                .longitude(store.getLocation().getX())
                .address(store.getAddress())
                .roadName(store.getRoadName())
                .roadAddress(store.getRoadAddress())
                .postCode(store.getPostCode())
                .hasCoupon(store.isHasCoupon())
                .tierBenefit(tierBenefitDtos)
                .distance(distance)
                .build();
    }

    public static MapStorePreviewResponse fromDetail(StoreDetailResponse detail) {
        StoreDto store = detail.getStore();
        PartnerDto partner = detail.getPartner();
        return MapStorePreviewResponse.builder()
                .storeId(store.getStoreId())
                .partnerId(partner.getPartnerId())
                .storeName(store.getStoreName())
                .partnerName(partner.getPartnerName())
                .category(partner.getCategory())
                .image(partner.getImage())
                .latitude(store.getLatitude())
                .longitude(store.getLongitude())
                .address(store.getAddress())
                .roadName(store.getRoadName())
                .roadAddress(store.getRoadAddress())
                .postCode(store.getPostCode())
                .hasCoupon(store.getHasCoupon())
                .tierBenefit(detail.getTierBenefit())
                .distance(detail.getDistance())
                .build();
    }
}
