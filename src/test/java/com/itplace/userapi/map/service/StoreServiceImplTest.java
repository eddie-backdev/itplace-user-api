package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
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
    void findNearbyPreviews_returnsFlatBenefitPreviewPayload() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("GS25")
                .image("https://example.com/gs25.png")
                .category("생활/편의")
                .build();
        Store store = store(1L, "GS25 강남점", partner, point(127.01, 37.50));
        store.setRoadAddress("서울 강남구 테헤란로 1");
        TierBenefitDto benefit = TierBenefitDto.builder()
                .benefitId(100L)
                .carrier(Carrier.SKT)
                .grade(Grade.SKT_VIP)
                .context("1천원 할인")
                .build();

        when(storeRepository.findStoreIdsInRadius(37.50, 127.00, 1_000, 900))
                .thenReturn(List.of(1L));
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(10L, List.of(new BenefitCacheDto(100L, "GS25 할인", List.of(benefit)))));

        List<MapStorePreviewResponse> result = storeService.findNearbyPreviews(
                37.50, 127.00, 1_000, 37.50, 127.00);

        assertThat(result).singleElement()
                .satisfies(preview -> {
                    assertThat(preview.getStoreId()).isEqualTo(1L);
                    assertThat(preview.getPartnerId()).isEqualTo(10L);
                    assertThat(preview.getStoreName()).isEqualTo("GS25 강남점");
                    assertThat(preview.getCategory()).isEqualTo("생활/편의");
                    assertThat(preview.getImage()).isEqualTo("https://example.com/gs25.png");
                    assertThat(preview.getRoadAddress()).isEqualTo("서울 강남구 테헤란로 1");
                    assertThat(preview.getTierBenefit()).containsExactly(benefit);
                });
        assertThat(MapStorePreviewResponse.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("store", "partner", "business", "city", "town", "legalDong");
    }

    @Test
    void findStoresInViewPreviews_usesProjectionRowsWithoutReloadingStoreEntities() {
        TierBenefitDto benefit = TierBenefitDto.builder()
                .benefitId(100L)
                .carrier(Carrier.SKT)
                .grade(Grade.SKT_VIP)
                .context("1천원 할인")
                .build();
        StorePreviewProjection farPreview = preview(
                1L,
                10L,
                "GS25 강남점",
                "GS25",
                "생활/편의",
                "https://example.com/gs25.png",
                37.510,
                127.010,
                "서울 강남구 역삼동",
                "테헤란로",
                "서울 강남구 테헤란로 1",
                "06234",
                true
        );
        StorePreviewProjection nearPreview = preview(
                2L,
                10L,
                "GS25 역삼점",
                "GS25",
                "생활/편의",
                "https://example.com/gs25.png",
                37.501,
                127.001,
                "서울 강남구 역삼동",
                "테헤란로",
                "서울 강남구 테헤란로 2",
                "06235",
                false
        );
        StorePreviewProjection stalePreview = preview(
                3L,
                10L,
                "스타벅스 역삼점",
                "GS25",
                "생활/편의",
                "https://example.com/gs25.png",
                37.500,
                127.000,
                "서울 강남구 역삼동",
                "테헤란로",
                "서울 강남구 테헤란로 3",
                "06236",
                true
        );

        when(storeRepository.findStorePreviewsInView(
                37.49, 37.52, 126.99, 127.02, 37.505, 127.005, null, 100))
                .thenReturn(List.of(farPreview, stalePreview, nearPreview));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(10L, List.of(new BenefitCacheDto(100L, "GS25 오프라인 혜택", List.of(benefit)))));

        List<MapStorePreviewResponse> result = storeService.findStoresInViewPreviews(
                37.49, 126.99, 37.52, 127.02, null, 37.50, 127.00);

        assertThat(result)
                .extracting(MapStorePreviewResponse::getStoreId)
                .containsExactly(2L, 1L);
        assertThat(result).first()
                .satisfies(preview -> {
                    assertThat(preview.getPartnerId()).isEqualTo(10L);
                    assertThat(preview.getPartnerName()).isEqualTo("GS25");
                    assertThat(preview.getRoadAddress()).isEqualTo("서울 강남구 테헤란로 2");
                    assertThat(preview.getTierBenefit()).containsExactly(benefit);
                });
        verify(storeRepository, never()).findAllByStoreIdInWithPartner(anyList());
    }

    @Test
    void findNearbyByCategory_returnsStoresSortedByDistance() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store farStore = store(1L, "카페 파트너 먼 매장", partner, point(127.10, 37.50));
        Store nearStore = store(2L, "카페 파트너 가까운 매장", partner, point(127.01, 37.50));

        when(storeRepository.findStoreIdsByCategoryWithinRadius("카페", 37.50, 127.00, 10_000, 900))
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
    void findNearby_fetchesBoundedCandidateWindowAndSamplesFinalLimitInServer() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("GS25")
                .category("생활/편의")
                .build();
        List<Long> candidateIds = IntStream.rangeClosed(1, 450)
                .mapToObj(Long::valueOf)
                .toList();

        when(storeRepository.findStoreIdsInRadius(37.50, 127.00, 1_000, 900))
                .thenReturn(candidateIds);
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> selectedIds = invocation.getArgument(0);
                    return selectedIds.stream()
                            .map(id -> store(id, "GS25 " + id, partner, point(127.00, 37.50)))
                            .toList();
                });
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearby(
                37.50, 127.00, 1_000, 37.50, 127.00);

        ArgumentCaptor<List<Long>> selectedIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storeRepository).findAllByStoreIdInWithPartner(selectedIdsCaptor.capture());
        assertThat(selectedIdsCaptor.getValue()).hasSize(300);
        assertThat(candidateIds).containsAll(selectedIdsCaptor.getValue());
        assertThat(result).hasSize(300);
    }

    @Test
    void findNearbyByKeyword_fallsBackToDatabaseSearchWhenElasticSearchFails() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store store = store(3L, "카페 파트너 매장", partner, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("카페", null))
                .thenThrow(new RuntimeException("elasticsearch unavailable"));
        when(storeRepository.searchNearbyStoreIds(127.00, 37.50, null, "카페"))
                .thenReturn(List.of(3L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(3L)))
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
        Store nearestStore = store(5L, "더벤티 강남점", partner, point(127.01, 37.50));
        Store fartherStore = store(4L, "더벤티 역삼점", partner, point(127.02, 37.50));

        when(storeSearchService.searchByKeyword("더벤티", null))
                .thenReturn(new StoreSearchResult(List.of(), List.of()));
        when(storeRepository.searchNearbyStoreIds(127.00, 37.50, null, "더벤티"))
                .thenReturn(List.of(5L, 4L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(5L, 4L)))
                .thenReturn(List.of(fartherStore, nearestStore));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, " 더벤티 ", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactly(5L, 4L);
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
        when(storeRepository.searchNearbyStoreIds(127.00, 37.50, "카페", "스타벅스"))
                .thenReturn(List.of(6L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(6L)))
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
    void findNearbyByKeyword_doesNotPrioritizeUnrelatedBrandTokenMatch() {
        Partner seoulLand = Partner.builder()
                .partnerId(21L)
                .partnerName("서울랜드")
                .category("액티비티")
                .build();
        Partner fancyLand = Partner.builder()
                .partnerId(22L)
                .partnerName("팬시랜드")
                .category("문구")
                .build();
        Store seoulLandStore = store(21L, "서울랜드 본점", seoulLand, point(127.04, 37.50));
        Store fancyLandStore = store(22L, "팬시랜드 문구", fancyLand, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("팬시랜드", null))
                .thenReturn(new StoreSearchResult(List.of(21L), List.of(22L)));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(21L, 22L)))
                .thenReturn(List.of(seoulLandStore, fancyLandStore));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(
                        21L, List.of(new BenefitCacheDto(21L, "서울랜드 자유이용권", List.of())),
                        22L, List.of(new BenefitCacheDto(22L, "팬시랜드 문구 할인", List.of()))
                ));

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, "팬시랜드", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getPartner().getPartnerName())
                .containsExactly("팬시랜드");
    }

    @Test
    void findNearbyByKeyword_fallsBackWhenElasticSearchOnlyReturnsUnrelatedBrandTokenMatch() {
        Partner seoulLand = Partner.builder()
                .partnerId(21L)
                .partnerName("서울랜드")
                .category("액티비티")
                .build();
        Partner fancyLand = Partner.builder()
                .partnerId(22L)
                .partnerName("팬시랜드")
                .category("문구")
                .build();
        Store seoulLandStore = store(21L, "서울랜드 본점", seoulLand, point(127.04, 37.50));
        Store fancyLandStore = store(22L, "팬시랜드 문구", fancyLand, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("팬시랜드", null))
                .thenReturn(new StoreSearchResult(List.of(21L), List.of()));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(21L)))
                .thenReturn(List.of(seoulLandStore));
        when(storeRepository.searchNearbyStoreIds(127.00, 37.50, null, "팬시랜드"))
                .thenReturn(List.of(22L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(22L)))
                .thenReturn(List.of(fancyLandStore));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(22L, List.of(new BenefitCacheDto(22L, "팬시랜드 문구 할인", List.of()))));

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, "팬시랜드", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getPartner().getPartnerName())
                .containsExactly("팬시랜드");
    }


    @Test
    void findNearbyByKeyword_filtersStaleStorePartnerRowsInDatabaseFallback() {
        Partner seoulLand = Partner.builder()
                .partnerId(21L)
                .partnerName("서울랜드")
                .category("액티비티")
                .build();
        Store staleFancyLandStore = store(22L, "팬시랜드 문구", seoulLand, point(127.01, 37.50));

        when(storeSearchService.searchByKeyword("팬시랜드", null))
                .thenReturn(new StoreSearchResult(List.of(), List.of()));
        when(storeRepository.searchNearbyStoreIds(127.00, 37.50, null, "팬시랜드"))
                .thenReturn(List.of(22L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(22L)))
                .thenReturn(List.of(staleFancyLandStore));

        List<StoreDetailResponse> result = storeService.findNearbyByKeyword(
                37.50, 127.00, null, "팬시랜드", 37.50, 127.00);

        assertThat(result).isEmpty();
    }

    @Test
    void findNearbyByPartnerName_filtersStaleStoreRowsBeforeAttachingBenefits() {
        Partner seoulSky = Partner.builder()
                .partnerId(31L)
                .partnerName("서울스카이")
                .category("액티비티")
                .build();
        Store staleSkyStore = store(31L, "박사콜스카이", seoulSky, point(127.01, 37.50));
        Store validSkyStore = store(32L, "롯데월드타워 서울스카이", seoulSky, point(127.02, 37.51));
        TierBenefitDto benefit = TierBenefitDto.builder()
                .benefitId(31L)
                .carrier(Carrier.KT)
                .grade(Grade.KT_VIP)
                .context("서울스카이 할인")
                .build();

        when(partnerRepository.findByPartnerName("서울스카이")).thenReturn(java.util.Optional.of(seoulSky));
        when(storeRepository.searchNearbyStoreIdsByPartnerId(127.00, 37.50, 31L))
                .thenReturn(List.of(31L, 32L));
        when(storeRepository.findAllByStoreIdInWithPartner(List.of(31L, 32L)))
                .thenReturn(List.of(validSkyStore, staleSkyStore));
        when(partnerBenefitCacheService.getBenefits(31L))
                .thenReturn(List.of(new BenefitCacheDto(31L, "서울스카이 혜택", List.of(benefit))));

        List<StoreDetailResponse> result = storeService.findNearbyByPartnerName(
                37.50, 127.00, "서울스카이", 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreName())
                .containsExactly("롯데월드타워 서울스카이");
    }

    @Test
    void findNearbyByCategory_removesDuplicateTierBenefitsFromMultipleBenefits() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("카페 파트너")
                .category("카페")
                .build();
        Store store = store(4L, "카페 파트너 매장", partner, point(127.01, 37.50));
        TierBenefitDto lguVip = TierBenefitDto.builder()
                .benefitId(1L)
                .carrier(Carrier.LGU)
                .grade(Grade.VIP)
                .context("아메리카노 10% 할인")
                .build();
        TierBenefitDto lguVvip = TierBenefitDto.builder()
                .benefitId(1L)
                .carrier(Carrier.LGU)
                .grade(Grade.VVIP)
                .context("아메리카노 10% 할인")
                .build();

        when(storeRepository.findStoreIdsByCategoryWithinRadius("카페", 37.50, 127.00, 10_000, 900))
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
        assertThat(result.get(0).getTierBenefit())
                .extracting(TierBenefitDto::getBenefitId)
                .containsExactly(1L, 1L);
    }

    @Test
    void findNearbyByCategory_keepsOfflineBenefitsForAllCarriers() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파스쿠찌")
                .category("카페")
                .build();
        Store store = store(7L, "파스쿠찌 강남점", partner, point(127.01, 37.50));
        TierBenefitDto skt = TierBenefitDto.builder()
                .benefitId(1L)
                .carrier(Carrier.SKT)
                .grade(Grade.SKT_GOLD)
                .context("SKT 골드 할인")
                .build();
        TierBenefitDto kt = TierBenefitDto.builder()
                .benefitId(2L)
                .carrier(Carrier.KT)
                .grade(Grade.KT_VIP)
                .context("KT VIP 할인")
                .build();
        TierBenefitDto lgu = TierBenefitDto.builder()
                .benefitId(3L)
                .carrier(Carrier.LGU)
                .grade(Grade.VIP)
                .context("LGU VIP 할인")
                .build();

        when(storeRepository.findStoreIdsByCategoryWithinRadius("카페", 37.50, 127.00, 10_000, 900))
                .thenReturn(List.of(7L));
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenReturn(List.of(store));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.of(10L, List.of(
                        new BenefitCacheDto(1L, "SKT 오프라인 혜택", List.of(skt)),
                        new BenefitCacheDto(2L, "KT 오프라인 혜택", List.of(kt)),
                        new BenefitCacheDto(3L, "LGU 오프라인 혜택", List.of(lgu))
                )));

        List<StoreDetailResponse> result = storeService.findNearbyByCategory(
                37.50, 127.00, 10_000, "카페", 37.50, 127.00);

        assertThat(result).singleElement()
                .extracting(StoreDetailResponse::getTierBenefit)
                .satisfies(tierBenefits -> assertThat(tierBenefits).containsExactly(skt, kt, lgu));
    }


    @Test
    void findNearbyDistributedForMap_samplesStoresFromGridCells() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("GS25")
                .category("생활/편의")
                .build();
        Store northWestStore = store(101L, "GS25 북서점", partner, point(126.98, 37.53));
        Store southEastStore = store(102L, "GS25 남동점", partner, point(127.04, 37.47));
        AtomicInteger cellCalls = new AtomicInteger();

        when(storeRepository.findStoreIdsInCellWithinRadius(
                org.mockito.ArgumentMatchers.<String>isNull(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenAnswer(invocation -> {
            int call = cellCalls.incrementAndGet();
            if (call == 1) {
                return List.of(101L);
            }
            if (call == 2) {
                return List.of(102L);
            }
            return List.of();
        });
        when(storeRepository.findAllByStoreIdInWithPartner(anyList()))
                .thenReturn(List.of(northWestStore, southEastStore));
        when(partnerBenefitCacheService.getBenefitsBatch(anyList()))
                .thenReturn(Map.<Long, List<BenefitCacheDto>>of());

        List<StoreDetailResponse> result = storeService.findNearbyDistributedForMap(
                37.50, 127.00, 5_000, null, 37.50, 127.00);

        assertThat(result)
                .extracting(response -> response.getStore().getStoreId())
                .containsExactlyInAnyOrder(101L, 102L);
        org.mockito.Mockito.verify(storeRepository, org.mockito.Mockito.times(25))
                .findStoreIdsInCellWithinRadius(
                        org.mockito.ArgumentMatchers.<String>isNull(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyInt()
                );
    }

    private static StorePreviewProjection preview(
            Long storeId,
            Long partnerId,
            String storeName,
            String partnerName,
            String category,
            String image,
            Double latitude,
            Double longitude,
            String address,
            String roadName,
            String roadAddress,
            String postCode,
            Boolean hasCoupon
    ) {
        return new StorePreviewProjection() {
            @Override
            public Long getStoreId() {
                return storeId;
            }

            @Override
            public Long getPartnerId() {
                return partnerId;
            }

            @Override
            public String getStoreName() {
                return storeName;
            }

            @Override
            public String getPartnerName() {
                return partnerName;
            }

            @Override
            public String getCategory() {
                return category;
            }

            @Override
            public String getImage() {
                return image;
            }

            @Override
            public Double getLatitude() {
                return latitude;
            }

            @Override
            public Double getLongitude() {
                return longitude;
            }

            @Override
            public String getAddress() {
                return address;
            }

            @Override
            public String getRoadName() {
                return roadName;
            }

            @Override
            public String getRoadAddress() {
                return roadAddress;
            }

            @Override
            public String getPostCode() {
                return postCode;
            }

            @Override
            public Boolean getHasCoupon() {
                return hasCoupon;
            }
        };
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
