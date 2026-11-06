package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.MapDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse;
import com.itplace.userapi.map.service.MapDirectionsRateLimiter;
import com.itplace.userapi.map.service.MapDirectionsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Map", description = "지도 기능 관련 API")
@RequestMapping("/api/v1/maps/stores")
@RequiredArgsConstructor
@Validated
public class MapDirectionsController {
    private final MapDirectionsService directionsService;
    private final MapDirectionsRateLimiter rateLimiter;

    @PostMapping(path = "/{storeId}/directions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MapDirectionsResponse>> directions(
            @PathVariable @Min(1) long storeId,
            @Valid @RequestBody MapDirectionsRequest request
    ) {
        if (!rateLimiter.check()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(ApiResponse.of(StoreCode.DIRECTIONS_RATE_LIMITED, null));
        }
        ApiResponse<MapDirectionsResponse> body = ApiResponse.of(
                StoreCode.DIRECTIONS_SUCCESS, directionsService.directions(storeId, request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
