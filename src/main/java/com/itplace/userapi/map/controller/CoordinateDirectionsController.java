package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.CoordinateDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse;
import com.itplace.userapi.map.service.MapDirectionsRateLimiter;
import com.itplace.userapi.map.service.MapDirectionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CoordinateDirectionsController {
    private final MapDirectionsService directionsService;
    private final MapDirectionsRateLimiter rateLimiter;

    @PostMapping(path = "/api/v1/maps/directions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MapDirectionsResponse>> directions(
            @Valid @RequestBody CoordinateDirectionsRequest request
    ) {
        if (!rateLimiter.check()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(ApiResponse.of(StoreCode.DIRECTIONS_RATE_LIMITED, null));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(StoreCode.DIRECTIONS_SUCCESS, directionsService.directions(request)));
    }
}
