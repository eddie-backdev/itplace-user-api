package com.itplace.userapi.map.dto.response;

import com.itplace.userapi.map.dto.PartnerDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.partner.entity.Partner;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class StoreDetailResponse {
    private StoreDto store;
    private PartnerDto partner;
    private List<TierBenefitDto> tierBenefit;
    private double distance;

    public static StoreDetailResponse of(Store store, Partner partner, List<TierBenefitDto> tierBenefitDtos, double distance) {
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

        PartnerDto partnerDto = PartnerDto.builder()
                .partnerId(partner.getPartnerId())
                .partnerName(partner.getPartnerName())
                .image(partner.getImage())
                .category(partner.getCategory() != null ? partner.getCategory().trim() : null)
                .build();

        return StoreDetailResponse.builder()
                .store(storeDto)
                .partner(partnerDto)
                .tierBenefit(tierBenefitDtos)
                .distance(distance)
                .build();
    }
}
