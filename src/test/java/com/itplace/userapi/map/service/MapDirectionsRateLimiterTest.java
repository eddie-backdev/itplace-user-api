package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoDirectionsClient.DirectionsProviderException;
import com.itplace.userapi.security.abuse.RedisRateLimitStore;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class MapDirectionsRateLimiterTest {
    private RedisRateLimitStore store;
    private MapDirectionsRateLimiter limiter;

    @BeforeEach
    void setUp() {
        store = mock(RedisRateLimitStore.class);
        limiter = new MapDirectionsRateLimiter(store);
        ReflectionTestUtils.setField(limiter, "limitPerMinute", 120L);
        ReflectionTestUtils.setField(limiter, "searchLimitPerMinute", 240L);
    }

    @Test
    void usesPlatformWideProviderCapAndAllowsConfiguredWindow() {
        when(store.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(120L);

        limiter.check();

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).increment(keyCaptor.capture(), eq(Duration.ofMinutes(1)));
        assertThat(keyCaptor.getValue()).isEqualTo("map:directions:provider:global");
    }

    @Test
    void overLimitHasSpecific429Code() {
        when(store.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(121L);

        assertThat(limiter.check()).isFalse();
    }

    @Test
    void redisFailureIsControlled503() {
        when(store.increment(anyString(), eq(Duration.ofMinutes(1))))
                .thenThrow(new DataAccessResourceFailureException("redis offline"));

        assertThatThrownBy(limiter::check)
                .isInstanceOf(DirectionsProviderException.class)
                .extracting(error -> ((DirectionsProviderException) error).getCode())
                .isEqualTo(StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE);
    }

    @Test
    void locationSearchUsesSeparatePlatformWideCap() {
        when(store.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(240L);

        limiter.checkLocationSearch();

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).increment(keyCaptor.capture(), eq(Duration.ofMinutes(1)));
        assertThat(keyCaptor.getValue()).isEqualTo("map:location-search:provider:global");
    }

    @Test
    void locationSearchRedisFailureIsControlledUnavailable() {
        when(store.increment(anyString(), eq(Duration.ofMinutes(1))))
                .thenThrow(new DataAccessResourceFailureException("redis offline"));

        assertThatThrownBy(limiter::checkLocationSearch)
                .isInstanceOf(com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException.class)
                .extracting(error -> ((com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException) error)
                        .getCode())
                .isEqualTo(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
    }
}
