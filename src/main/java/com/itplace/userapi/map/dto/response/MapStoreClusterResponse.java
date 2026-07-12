package com.itplace.userapi.map.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MapStoreClusterResponse {
    private String clusterId;
    private String category;
    private String administrativeUnitType;
    private String administrativeUnitName;
    private Integer targetMapLevel;
    private Double latitude;
    private Double longitude;
    private Long count;
}
