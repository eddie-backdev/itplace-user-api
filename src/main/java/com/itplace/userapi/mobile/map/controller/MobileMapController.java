package com.itplace.userapi.mobile.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.mobile.map.dto.MobileMapNearbyResponse;
import com.itplace.userapi.mobile.map.service.MobileMapService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Mobile Map", description = "모바일 지도 탐색 API")
@RequestMapping("/api/v1/mobile/map")
@RequiredArgsConstructor
@Validated
public class MobileMapController {
    private final MobileMapService mobileMapService;

    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<MobileMapNearbyResponse>> getNearby(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(value = "userLat", required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double userLat,
            @RequestParam(value = "userLng", required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double userLng,
            @RequestParam(value = "radiusMeters", defaultValue = "800") @DecimalMin("1.0") @DecimalMax("400000.0") double radiusMeters,
            @RequestParam(value = "carrier", required = false) String carrier,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "partnerName", required = false) String partnerName
    ) {
        boolean hasUserLocation = userLat != null && userLng != null;
        MobileMapNearbyResponse response = mobileMapService.findNearby(
                lat,
                lng,
                hasUserLocation ? userLat : lat,
                hasUserLocation ? userLng : lng,
                radiusMeters,
                carrier,
                category,
                keyword,
                partnerName
        );
        ApiResponse<MobileMapNearbyResponse> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, response);
        return body.toResponseEntity();
    }
}
