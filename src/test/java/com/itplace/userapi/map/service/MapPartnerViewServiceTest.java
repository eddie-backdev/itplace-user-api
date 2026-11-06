package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.MapViewException;
import com.itplace.userapi.map.repository.MapPartnerViewRepository;
import com.itplace.userapi.map.repository.StoreRepository;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapPartnerViewServiceTest {
    private StoreRepository storeRepository;
    private MapPartnerViewRepository viewRepository;
    private MapPartnerViewService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        viewRepository = mock(MapPartnerViewRepository.class);
        service = new MapPartnerViewService(storeRepository, viewRepository);
        when(storeRepository.findActiveStoreWithPartner(42L)).thenReturn(Optional.of(Store.builder().storeId(42L).build()));
    }

    @Test
    void hashesCanonicalUuidV4WithoutRetainingRawIdentifier() {
        String hash = service.hashAnonymousSessionId("d9428888-122b-4c94-b775-701c9c3a0001");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}")
                .isNotEqualTo("d9428888-122b-4c94-b775-701c9c3a0001");
        assertThat(service.hashAnonymousSessionId("D9428888-122B-4C94-B775-701C9C3A0001")).isEqualTo(hash);
    }

    @Test
    void rejectsMissingMalformedOrNonV4GuestSessionIds() {
        assertThatThrownBy(() -> service.hashAnonymousSessionId(null))
                .isInstanceOf(MapViewException.class)
                .extracting("code").isEqualTo(StoreCode.MAP_VIEW_INVALID_SESSION);
        assertThatThrownBy(() -> service.hashAnonymousSessionId("not-a-uuid"))
                .isInstanceOf(MapViewException.class);
        assertThatThrownBy(() -> service.hashAnonymousSessionId("d9428888-122b-1c94-b775-701c9c3a0001"))
                .isInstanceOf(MapViewException.class);
    }

    @Test
    void recordsLoggedInUserWithoutGuestIdentityOnSeoulCalendarDay() {
        service.recordStoreView(42L, 17L, "ignored-session-hash");

        verify(viewRepository).recordIfEligible(42L, 17L, null,
                LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    @Test
    void recordsGuestSessionHashAndRejectsInactiveStore() {
        String hash = service.hashAnonymousSessionId("d9428888-122b-4c94-b775-701c9c3a0001");
        service.recordStoreView(42L, null, hash);
        verify(viewRepository).recordIfEligible(42L, null, hash,
                LocalDate.now(ZoneId.of("Asia/Seoul")));

        when(storeRepository.findActiveStoreWithPartner(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordStoreView(99L, 17L, null))
                .isInstanceOf(MapViewException.class)
                .extracting("code").isEqualTo(StoreCode.STORE_NOT_FOUND);
    }

    @Test
    void usesSeoulCalendarDayAcrossUtcMidnightBoundary() {
        var beforeSeoulMidnight = new MapPartnerViewService(storeRepository, viewRepository,
                Clock.fixed(Instant.parse("2026-11-07T14:59:59Z"), ZoneOffset.UTC));
        var afterSeoulMidnight = new MapPartnerViewService(storeRepository, viewRepository,
                Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC));

        beforeSeoulMidnight.recordStoreView(42L, 17L, null);
        afterSeoulMidnight.recordStoreView(42L, 17L, null);

        verify(viewRepository).recordIfEligible(42L, 17L, null, LocalDate.of(2026, 11, 7));
        verify(viewRepository).recordIfEligible(42L, 17L, null, LocalDate.of(2026, 11, 8));
    }
}
