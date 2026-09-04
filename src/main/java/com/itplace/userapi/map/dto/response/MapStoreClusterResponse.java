package com.itplace.userapi.map.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
