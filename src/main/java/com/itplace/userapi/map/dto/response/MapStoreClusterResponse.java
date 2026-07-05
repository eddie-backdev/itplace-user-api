package com.itplace.userapi.map.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MapStoreClusterResponse {
    private String clusterId;
    private String category;
    private Double latitude;
    private Double longitude;
    private Long count;
}
