package com.itplace.userapi.map.service;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoDirectionsClient.DirectionsProviderException;
import com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException;
import com.itplace.userapi.map.exception.MapViewException;
import com.itplace.userapi.security.abuse.RedisRateLimitStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MapDirectionsRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final RedisRateLimitStore rateLimitStore;

    @Value("${kakao.directions.rate-limit-per-minute:120}")
    private long limitPerMinute;

    @Value("${kakao.local.search-rate-limit-per-minute:240}")
    private long searchLimitPerMinute;

    @Value("${app.map.partner-view-rate-limit-per-minute:6000}")
    private long partnerViewLimitPerMinute;

    public boolean check() {
        try {
            // API ingress does not expose a verified client-IP chain here; this cap protects the provider globally.
            String key = "map:directions:provider:global";
            return rateLimitStore.increment(key, WINDOW) <= limitPerMinute;
        } catch (DataAccessException e) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE);
        }
    }

    public boolean checkLocationSearch() {
        try {
            return rateLimitStore.increment("map:location-search:provider:global", WINDOW) <= searchLimitPerMinute;
        } catch (DataAccessException e) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
        }
    }

    public boolean checkPartnerView() {
        try {
            return rateLimitStore.increment("map:partner-view:global", WINDOW) <= partnerViewLimitPerMinute;
        } catch (DataAccessException e) {
            throw new MapViewException(StoreCode.MAP_VIEW_UNAVAILABLE);
        }
    }

}
