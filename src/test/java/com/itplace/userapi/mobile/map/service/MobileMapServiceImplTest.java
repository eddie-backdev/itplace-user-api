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
        when(storeService.findNearbyDistributedForMap(37.5, 127.0, 800, null, 37.5, 127.0))
                .thenReturn(List.of(store(1L, "GS25 강남점", Carrier.LGU)));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 800, null, null, null, null);

        assertThat(response.center().lat()).isEqualTo(37.5);
        assertThat(response.radiusMeters()).isEqualTo(800);
        assertThat(response.markers()).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.storeId()).isEqualTo(1L);
                    assertThat(marker.partnerName()).isEqualTo("GS25");
                    assertThat(marker.logoUrl()).isEqualTo("https://cdn.example/gs25.png");
                    assertThat(marker.benefitSummary()).isEqualTo("LGU 할인");
                });
    }

    @Test
    void findNearby_filtersMarkersByCarrierForMobilePayload() {
        when(storeService.findNearbyDistributedForMap(37.5, 127.0, 800, null, 37.5, 127.0))
                .thenReturn(List.of(
                        store(1L, "GS25 강남점", Carrier.LGU),
                        store(2L, "GS25 역삼점", Carrier.SKT)
                ));

        MobileMapNearbyResponse response = mobileMapService.findNearby(
                37.5, 127.0, 800, "LGU", null, null, null);

        assertThat(response.markers())
                .extracting(marker -> marker.storeId())
                .containsExactly(1L);
    }

    private static StoreDetailResponse store(long id, String storeName, Carrier carrier) {
        TierBenefitDto tier = TierBenefitDto.builder()
                .benefitId(id)
                .carrier(carrier)
                .grade(Grade.VIP)
                .context(carrier + " 할인")
                .build();
        return StoreDetailResponse.builder()
                .store(StoreDto.builder()
                        .storeId(id)
                        .storeName(storeName)
                        .latitude(37.5)
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
                .distance(120)
                .build();
    }
}
