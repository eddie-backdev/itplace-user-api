package com.itplace.userapi.map.dto.response;

import com.itplace.userapi.map.dto.PartnerDto;
import com.itplace.userapi.map.dto.response.StoreDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.partner.entity.Partner;
import lombok.Builder;
import lombok.Getter;

/** Public location facts used by crawlable store pages. No branch eligibility is implied. */
@Getter
@Builder
public class StoreSeoResponse {
    private StoreDto store;
    private PartnerDto partner;

    public static StoreSeoResponse of(Store store, Partner partner) {
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
                .category(partner.getCategory() == null ? null : partner.getCategory().trim())
                .build();
        return StoreSeoResponse.builder().store(storeDto).partner(partnerDto).build();
    }
}
