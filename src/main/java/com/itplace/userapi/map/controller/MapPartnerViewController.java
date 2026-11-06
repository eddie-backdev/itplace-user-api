package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.service.MapDirectionsRateLimiter;
import com.itplace.userapi.map.service.MapPartnerViewService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Min;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Map", description = "지도 기능 관련 API")
@RequestMapping("/api/v1/maps/stores")
@RequiredArgsConstructor
@Validated
public class MapPartnerViewController {
    private final MapPartnerViewService viewService;
    private final MapDirectionsRateLimiter rateLimiter;

    @PostMapping("/{storeId}/view")
    public ResponseEntity<ApiResponse<Void>> recordStoreView(
            @PathVariable @Min(1) long storeId,
            @RequestHeader(name = "X-Map-View-Session", required = false) String anonymousSessionId
    ) {
        Long userId = authenticatedUserId();
        String anonymousSessionHash = userId == null
                ? viewService.hashAnonymousSessionId(anonymousSessionId)
                : null;
        if (!rateLimiter.checkPartnerView()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(ApiResponse.of(StoreCode.MAP_VIEW_RATE_LIMITED, null));
        }
        viewService.recordStoreView(storeId, userId, anonymousSessionHash);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(StoreCode.MAP_VIEW_SUCCESS, null));
    }

    private Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        return principal instanceof PrincipalDetails details ? details.getUserId() : null;
    }
}
