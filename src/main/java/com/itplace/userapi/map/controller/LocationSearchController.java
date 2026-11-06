package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.LocationSearchRequest;
import com.itplace.userapi.map.dto.LocationSearchResponse;
import com.itplace.userapi.map.service.LocationSearchService;
import com.itplace.userapi.map.service.MapDirectionsRateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/maps/locations")
@RequiredArgsConstructor
public class LocationSearchController {
    private final LocationSearchService locationSearchService;
    private final MapDirectionsRateLimiter rateLimiter;

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<LocationSearchResponse>> search(
            @Valid @RequestBody LocationSearchRequest request
    ) {
        if (!rateLimiter.checkLocationSearch()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(ApiResponse.of(StoreCode.LOCATION_SEARCH_RATE_LIMITED, null));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(StoreCode.LOCATION_SEARCH_SUCCESS, locationSearchService.search(request)));
    }
}
