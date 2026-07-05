package com.itplace.userapi.map.repository.projection;

public interface StoreClusterProjection {
    String getClusterId();

    String getCategory();

    Double getLatitude();

    Double getLongitude();

    Long getCount();
}
