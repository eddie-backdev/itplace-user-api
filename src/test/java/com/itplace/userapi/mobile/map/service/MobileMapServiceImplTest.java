package com.itplace.userapi.mobile.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.map.dto.PartnerDto;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.StoreDto;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.mobile.map.dto.MobileMapNearbyResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileMapServiceImplTest {
    @Mock
    private StoreService storeService;

    @InjectMocks
    private MobileMapServiceImpl mobileMapService;

    @Test
    void findNearby_returnsLightweightMarkersFromExistingStoreService() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, null, null, null, null))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, null, null, null, null);

        assertThat(response.center().lat()).isEqualTo(37.5);
        assertThat(response.radiusMeters()).isEqualTo(800);
        assertThat(response.markers()).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.storeId()).isEqualTo(1L);
                    assertThat(marker.partnerName()).isEqualTo("GS25");
                    assertThat(marker.logoUrl()).isEqualTo("https://cdn.example/gs25.png");
                    assertThat(marker.distanceMeters()).isEqualTo(120.0);
                    assertThat(marker.benefitSummary()).isEqualTo("LGU 할인");
                    assertThat(marker.tierBenefits()).hasSize(1);
                });
    }

    @Test
    void findNearby_keepsSearchCenterSeparateFromUserDistanceOrigin() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.49, 126.99, 800, null, null, null, null))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU, 1.2)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.49, 126.99, 800, null, null, null, null);

        assertThat(response.center().lat()).isEqualTo(37.5);
        assertThat(response.markers()).singleElement()
                .satisfies(marker -> assertThat(marker.distanceMeters()).isEqualTo(1_200.0));
    }

    @Test
    void findNearbyConvertsCarrierFilteredStoreServiceResults() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, "LGU", null, null, null))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, "LGU", null, null, null);

        assertThat(response.markers())
                .extracting(marker -> marker.storeId())
                .containsExactly(1L);
        assertThat(response.markers().get(0).tierBenefits())
                .extracting(TierBenefitDto::getCarrier)
                .containsExactly(Carrier.LGU);
    }

    @Test
    void findNearbyReturnsEmptyMarkersWhenStoreServiceRejectsAllCarrierCandidates() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, "LGU", null, null, null))
                .thenReturn(List.of());

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, "LGU", null, null, null);

        assertThat(response.markers()).isEmpty();
    }

    @Test
    void findNearby_usesNeutralSummaryWhenBenefitDetailsAreUnavailable() {
        StoreDetailResponse noBenefit = store(1L, "GS25 강남점", Carrier.LGU);
        noBenefit.setTierBenefit(List.of());
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, null, null, null, null))
                .thenReturn(List.of(noBenefit));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, null, null, null, null);

        assertThat(response.markers()).singleElement()
                .satisfies(marker -> assertThat(marker.benefitSummary())
                        .isEqualTo("혜택 정보를 확인할 수 없습니다."));
    }

    @Test
    void findNearbyUsesKeywordCandidatesFilteredByStoreService() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, null, null, "커피", null))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU, 0.5, 37.5045)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, null, null, "커피", null);

        assertThat(response.markers())
                .extracting(marker -> marker.storeId())
                .containsExactly(1L);
    }

    @Test
    void findNearbyUsesPartnerCandidatesFilteredByStoreService() {
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, null, null, null, "GS25"))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU, 0.7, 37.5063)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, null, null, null, "GS25");

        assertThat(response.markers())
                .extracting(marker -> marker.storeId())
                .containsExactly(1L);
    }

    @Test
    void findNearby_returnsOnlyRepresentativeTierForMarkerSummary() {
        StoreDetailResponse store = store(1L, "GS25 강남점", Carrier.LGU);
        store.setTierBenefit(List.of(
                tier(1L, Carrier.LGU, "LGU 할인"),
                tier(2L, Carrier.SKT, "SKT 할인")
        ));
        when(storeService.findNearbyForMobile(37.5, 127.0, 37.5, 127.0, 800, null, null, null, null))
                .thenReturn(List.of(store));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 37.5, 127.0, 800, null, null, null, null);

        assertThat(response.markers()).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.benefitSummary()).isEqualTo("LGU 할인");
                    assertThat(marker.tierBenefits())
                            .extracting(TierBenefitDto::getBenefitId)
                            .containsExactly(1L);
                });
    }

    private static StoreDetailResponse store(long id, String storeName, Carrier carrier) {
        return store(id, storeName, carrier, 0.12);
    }

    private static StoreDetailResponse store(long id, String storeName, Carrier carrier, double distanceKm) {
        return store(id, storeName, carrier, distanceKm, 37.5);
    }

    private static StoreDetailResponse store(
            long id,
            String storeName,
            Carrier carrier,
            double distanceKm,
            double latitude
    ) {
        TierBenefitDto tier = tier(id, carrier, carrier + " 할인");
        return StoreDetailResponse.builder()
                .store(StoreDto.builder()
                        .storeId(id)
                        .storeName(storeName)
                        .latitude(latitude)
                        .longitude(127.0)
                        .hasCoupon(true)
                        .build())
                .partner(PartnerDto.builder()
                        .partnerId(10L)
                        .partnerName("GS25")
                        .image("https://cdn.example/gs25.png")
                        .category("생활/편의")
                        .build())
                .tierBenefit(List.of(tier))
                .distance(distanceKm)
                .build();
    }

    private static TierBenefitDto tier(long benefitId, Carrier carrier, String context) {
        return TierBenefitDto.builder()
                .benefitId(benefitId)
                .carrier(carrier)
                .grade(Grade.VIP)
                .context(context)
                .build();
    }
}
