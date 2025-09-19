package com.itplace.userapi.map.dto;

import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.partner.entity.Partner;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class StoreDetailDto {
    private StoreDto store;
    private PartnerDto partner;
    private List<TierBenefitDto> tierBenefit;
    private double distance;

    public static StoreDetailDto of(Store store, Partner partner, List<TierBenefitDto> tierBenefitDtos, double distance) {
        // Store 엔티티를 StoreDto로 변환
        StoreDto storeDto = StoreDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .business(store.getBusiness())
                .city(store.getCity())
                .town(store.getTown())
                .legalDong(store.getLegalDong())
                .address(store.getAddress())
                .roadName(store.getRoadName())
                .roadAddress(store.getRoadAddress())
                .postCode(store.getPostCode())
                .longitude(store.getLocation().getX())
                .latitude(store.getLocation().getY())
                .hasCoupon(store.isHasCoupon())
                .build();

        // Partner 엔티티를 PartnerDto로 변환
        PartnerDto partnerDto = PartnerDto.builder()
                .partnerId(partner.getPartnerId())
                .partnerName(partner.getPartnerName())
                .image(partner.getImage())
                .category(partner.getCategory().trim())
                .build();

        // 최종 StoreDetailDto 빌드 및 반환
        return StoreDetailDto.builder()
                .store(storeDto)
                .partner(partnerDto)
                .tierBenefit(tierBenefitDtos)
                .distance(distance)
                .build();
    }
}
