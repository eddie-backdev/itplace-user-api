package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreServiceImplTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private PartnerBenefitCacheService partnerBenefitCacheService;

    @Mock
    private StoreSearchService storeSearchService;

    @InjectMocks
    private StoreServiceImpl storeService;

    @Test
    void findNearbyByCategory_returnsStoresSortedByDistance() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store farStore = store(1L, "먼 매장", partner, point(127.10, 37.50));
        Store nearStore = store(2L, "가까운 매장", partner, point(127.01, 37.50));

        when(storeRepository.findRandomStoreIdsByCategory("카페", 37.50, 127.00, 10_000, 50))
                .thenReturn(List.of(1L, 2L));
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenReturn(List.of(farStore, nearStore));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyByCategory(
                37.50, 127.00, 10_000, "카페", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactly(2L, 1L);
        assertThat(result)
                .extracting(StoreDetailResponse::getDistance)
                .isSorted();
    }

    @Test
    void findNearbyByKeyword_fallsBackToDatabaseSearchWhenElasticSearchFails() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store store = store(3L, "카페 매장", partner, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("카페", null))
                .thenThrow(new RuntimeException("elasticsearch unavailable"));
        when(storeRepository.searchNearbyStores(127.00, 37.50, null, "카페"))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, "카페", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactly(3L);
    }

    private static Store store(long id, String name, Partner partner, Point location) {
        return Store.builder()
                .storeId(id)
                .storeName(name)
                .business("business")
                .partner(partner)
                .location(location)
                .build();
    }

    private static Point point(double lng, double lat) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
    }
}
