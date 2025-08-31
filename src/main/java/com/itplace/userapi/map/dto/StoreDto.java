package com.itplace.userapi.map.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class StoreDto {
    private long storeId;
    private String storeName;
    private String business;
    private String city;
    private String town;
    private String legalDong;
    private String address;
    private String roadName;
    private String roadAddress;
    private String postCode;
    private Double longitude;
    private Double latitude;
    private Boolean hasCoupon;
}
