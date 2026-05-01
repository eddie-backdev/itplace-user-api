package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
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

        when(storeRepository.findRandomStoreIdsByCategory("카페", 37.50, 127.00, 10_000, 300))
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

    @Test
    void findNearbyByKeyword_fallsBackToDatabaseSearchWhenElasticSearchReturnsEmptyResult() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("더벤티")
                .category("카페")
                .build();
        Store store = store(5L, "더벤티 강남점", partner, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("더벤티", null))
                .thenReturn(new StoreSearchResult(List.of(), List.of()));
        when(storeRepository.searchNearbyStores(127.00, 37.50, null, "더벤티"))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, " 더벤티 ", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactly(5L);
    }

    @Test
    void findNearbyByKeyword_fallsBackToDatabaseSearchWhenElasticSearchIdsAreStale() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("스타벅스")
                .category("카페")
                .build();
        Store store = store(6L, "스타벅스 역삼점", partner, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("스타벅스", "카페"))
                .thenReturn(new StoreSearchResult(List.of(999L), List.of()));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(999L)))
                .thenReturn(List.of());
        when(storeRepository.searchNearbyStores(127.00, 37.50, "카페", "스타벅스"))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, "카페", "스타벅스", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactly(6L);
    }

    @Test
    void findNearbyByCategory_removesDuplicateTierBenefitsFromMultipleBenefits() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store store = store(4L, "카페 매장", partner, point(127.01, 37.50));
        TierBenefitDto lguVip = TierBenefitDto.builder()
                .carrier(Carrier.LGU)
                .grade(Grade.VIP)
                .context("아메리카노 10% 할인")
                .build();
        TierBenefitDto lguVvip = TierBenefitDto.builder()
                .carrier(Carrier.LGU)
                .grade(Grade.VVIP)
                .context("아메리카노 10% 할인")
                .build();

        when(storeRepository.findRandomStoreIdsByCategory("카페", 37.50, 127.00, 10_000, 300))
                .thenReturn(List.of(4L));
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(10L, List.of(
                        new BenefitCacheDto(1L, "중복 혜택 A", List.of(lguVip, lguVvip)),
                        new BenefitCacheDto(2L, "중복 혜택 B", List.of(lguVip))
                )));

        List<StoreDetailResponse> result = storeService.findNearbyByCategory(
                37.50, 127.00, 10_000, "카페", 37.50, 127.00);

        assertThat(result).singleElement()
                .extracting(StoreDetailResponse::getTierBenefit)
                .satisfies(tierBenefits -> assertThat(tierBenefits).containsExactly(lguVip, lguVvip));
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
