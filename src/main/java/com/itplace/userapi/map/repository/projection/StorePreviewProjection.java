package com.itplace.userapi.map.repository.projection;

public interface StorePreviewProjection {
    Long getStoreId();

    Long getPartnerId();

    String getStoreName();

    String getPartnerName();

    String getCategory();

    String getImage();

    Double getLatitude();

    Double getLongitude();

    String getAddress();

    String getRoadName();

    String getRoadAddress();

    String getPostCode();

    Boolean getHasCoupon();
}
