package com.itplace.userapi.map.repository.projection;

public interface StoreClusterProjection {
    String getClusterId();

    String getCategory();

    String getAdministrativeUnitType();

    String getAdministrativeUnitName();

    Double getLatitude();

    Double getLongitude();

    Long getCount();
}
