package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.MapStorePreviewBatchResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorePreviewBatchContractTest {
    @Mock StoreRepository repository;
    @Mock PartnerBenefitCacheService cache;
    @Mock StoreSearchService search;
    @Mock StorePreviewQueryService previewQuery;
    @InjectMocks StoreServiceImpl service;
    final ObjectMapper mapper = new ObjectMapper();
    final Partner partner = Partner.builder().partnerId(10L).partnerName("GS25").category("생활/편의")
            .image("https://example.com/gs25.png").build();

    @ParameterizedTest
    @ValueSource(strings = {"offline", "legacy-offline", "common", "per-store"})
    void expandingCompactKeepsEveryLegacyFieldAndBenefitSelection(String policy) {
        List<Store> stores = List.of(store(1, "GS25 첫점", partner, 37.501),
                store(2, "GS25 둘째점", partner, 37.502), store(3, "GS25 나머지점", partner, 37.503));
        List<BenefitCacheDto> benefits = switch (policy) {
            case "offline" -> List.of(benefit(100, "공통", UsageType.BOTH, "할인"),
                    benefit(200, "온라인", UsageType.ONLINE, "온라인 전용"));
            case "legacy-offline" -> List.of(benefit(100, "오프라인 공통", null, "할인"),
                    benefit(200, "온라인", null, "온라인 전용"));
            case "per-store" -> List.of(benefit(100, "GS25 첫점", null, "첫점 할인"),
                    new BenefitCacheDto(200L, "GS25 둘째점", List.of()),
                    benefit(300, "다른 매장", null, "다른 할인"));
            default -> List.of(benefit(100, "공통A", null, "할인"), benefit(200, "공통B", null, "할인"));
        };
        stubNearby(stores, benefits);
        var legacy = service.findNearbyPreviews(37.5, 127, 1000, 37.5, 127);
        var compact = service.findNearbyPreviewBatch(37.5, 127, 1000, null, 37.5, 127);
        assertThat(expand(compact)).isEqualTo(mapper.valueToTree(legacy));
        if (policy.equals("per-store")) {
            assertThat(compact.getStores().get(0).getTierBenefit()).isNull();
            assertThat(compact.getStores().get(1).getTierBenefit()).isEmpty();
            assertThat(compact.getStores().get(2).getTierBenefit()).hasSize(2);
        }
    }

    @Test
    void commonTiersArePreparedOnceForThreeHundredStoresAndPayloadShrinks() throws Exception {
        List<Store> stores = IntStream.rangeClosed(1, 300)
                .mapToObj(id -> store(id, "GS25 지점" + id, partner, 37.5 + id * .000001)).toList();
        BenefitCacheDto benefit = spy(benefit(100, "혜택", UsageType.OFFLINE, "브랜드 공통 할인 안내 ".repeat(40)));
        stubNearby(stores, List.of(benefit));
        byte[] legacy = mapper.writeValueAsBytes(service.findNearbyPreviews(37.5, 127, 1000, 37.5, 127));
        clearInvocations(benefit);
        var compact = service.findNearbyPreviewBatch(37.5, 127, 1000, null, 37.5, 127);
        assertThat(compact.getStores()).hasSize(300).allSatisfy(store -> assertThat(store.getTierBenefit()).isNull());
        assertThat(compact.getPartners()).hasSize(1);
        verify(benefit, times(1)).getTierBenefits();
        assertThat(mapper.writeValueAsBytes(compact).length).isLessThan(legacy.length / 3);
    }

    @Test
    void keywordCompactPreservesBrandPriorityAheadOfNearerNameMatchAndZeroDistance() {
        Partner other = Partner.builder().partnerId(20L).partnerName("카페").build();
        List<Store> stores = List.of(store(1, "GS25 먼점", partner, 37.6),
                store(2, "카페 GS25 근처", other, 37.5001));
        when(search.searchByKeyword("GS25", null)).thenReturn(new StoreSearchResult(List.of(1L), List.of(2L)));
        when(repository.searchEligibleNearbyStoreIds(127, 37.5, null, "GS25")).thenReturn(List.of(2L, 1L));
        when(repository.findEligibleByStoreIdInWithPartner(anyList())).thenReturn(stores);
        when(cache.getBenefitsBatch(anyList())).thenReturn(Map.of());
        var result = service.findNearbyByKeywordPreviewBatch(37.5, 127, null, "GS25", 37.5, 127);
        assertThat(result.getStores()).extracting(MapStorePreviewBatchResponse.StorePreview::getStoreId)
                .containsExactly(1L, 2L);
        assertThat(result.getStores().get(0).getDistance()).isGreaterThan(result.getStores().get(1).getDistance());
        var zero = service.findNearbyByKeywordPreviewBatch(37.5, 127, null, "GS25", 0, 127);
        assertThat(zero.getStores()).allSatisfy(store -> assertThat(store.getDistance()).isZero());
        verify(repository, never()).searchNearbyStoreIds(anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void keywordFallbackKeepsDatabaseOrderAndSkipsLegacyEligibility() {
        when(search.searchByKeyword("GS25", null)).thenThrow(new IllegalStateException("offline"));
        when(repository.searchEligibleNearbyStoreIds(127, 37.5, null, "GS25")).thenReturn(List.of(2L, 1L));
        when(repository.findEligibleByStoreIdInWithPartner(List.of(2L, 1L)))
                .thenReturn(List.of(store(1, "GS25 하나", partner, 37.501), store(2, "GS25 둘", partner, 37.502)));
        when(cache.getBenefitsBatch(List.of(10L))).thenReturn(Map.of());
        assertThat(service.findNearbyByKeywordPreviewBatch(37.5, 127, null, "GS25", 37.5, 127).getStores())
                .extracting(MapStorePreviewBatchResponse.StorePreview::getStoreId).containsExactly(2L, 1L);
        verify(repository, never()).findAllByStoreIdInWithPartner(anyList());
    }

    @Test
    void categoryIsNormalizedAndRowsRemovedBetweenQueriesReturnEmptyWithoutLoadingBenefits() {
        when(repository.findEligibleStoreIdsWithinRadius("푸드", 37.5, 127, 1000, 900)).thenReturn(List.of(1L));
        when(repository.findEligibleByStoreIdInWithPartner(anyList())).thenReturn(List.of());
        var empty = service.findNearbyPreviewBatch(37.5, 127, 1000, " 푸드 ", 37.5, 127);
        assertThat(empty.getStores()).isEmpty();
        assertThat(empty.getPartners()).isEmpty();
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void viewportKeepsFirstStorePartnerTiersForOldClientsAndOverridesCommonStore(boolean firstEmpty) {
        var first = mock(com.itplace.userapi.map.repository.projection.StorePreviewProjection.class);
        var second = mock(com.itplace.userapi.map.repository.projection.StorePreviewProjection.class);
        when(first.getStoreId()).thenReturn(1L);
        when(first.getPartnerId()).thenReturn(10L);
        when(first.getStoreName()).thenReturn("GS25 첫점");
        when(second.getStoreId()).thenReturn(2L);
        when(second.getPartnerId()).thenReturn(10L);
        when(second.getStoreName()).thenReturn("GS25 공통점");
        when(previewQuery.findStorePreviewsInView(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(), anyInt())).thenReturn(List.of(first, second));
        BenefitCacheDto specific = firstEmpty ? new BenefitCacheDto(100L, "GS25 첫점", List.of())
                : benefit(100, "GS25 첫점", null, "첫점 전용");
        when(cache.getBenefitsBatch(List.of(10L))).thenReturn(Map.of(10L,
                List.of(specific, benefit(200, "다른점", null, "다른점 할인"), benefit(300, "셋째점", null, "셋째점 할인"))));
        var result = service.findStoresInViewPreviewBatch(37.49, 126.99, 37.51, 127.01, null, 300);
        assertThat(result.getPartners().get(0).getTierBenefit()).extracting(TierBenefitDto::getBenefitId)
                .containsExactlyElementsOf(firstEmpty ? List.of() : List.of(100L));
        assertThat(result.getStores().get(0).getTierBenefit()).isNull();
        assertThat(result.getStores().get(1).getTierBenefit()).extracting(TierBenefitDto::getBenefitId)
                .containsExactlyElementsOf(firstEmpty ? List.of(200L, 300L) : List.of(100L, 200L, 300L));
    }

    private void stubNearby(List<Store> stores, List<BenefitCacheDto> benefits) {
        List<Long> ids = stores.stream().map(Store::getStoreId).toList();
        when(repository.findStoreIdsInRadius(37.5, 127, 1000, 900)).thenReturn(ids);
        when(repository.findAllByStoreIdInWithPartner(anyList())).thenReturn(stores);
        when(repository.findEligibleStoreIdsWithinRadius(null, 37.5, 127, 1000, 900)).thenReturn(ids);
        when(repository.findEligibleByStoreIdInWithPartner(anyList())).thenReturn(stores);
        when(cache.getBenefitsBatch(List.of(10L))).thenReturn(Map.of(10L, benefits));
    }

    private JsonNode expand(MapStorePreviewBatchResponse batch) {
        var result = mapper.createArrayNode();
        for (var store : batch.getStores()) {
            var partner = batch.getPartners().stream().filter(p -> p.getPartnerId().equals(store.getPartnerId())).findFirst().orElseThrow();
            ObjectNode row = mapper.valueToTree(store);
            row.put("roadName", store.getRoadName());
            row.put("partnerName", partner.getPartnerName());
            row.put("category", partner.getCategory());
            row.put("image", partner.getImage());
            row.set("tierBenefit", mapper.valueToTree(store.getTierBenefit() == null ? partner.getTierBenefit() : store.getTierBenefit()));
            result.add(row);
        }
        return result;
    }

    private BenefitCacheDto benefit(long id, String name, UsageType usage, String context) {
        return new BenefitCacheDto(id, name, usage, null, List.of(TierBenefitDto.builder().benefitId(id)
                .carrier(Carrier.SKT).grade(Grade.SKT_VIP).context(context).offlineContext(context).build()));
    }

    private Store store(long id, String name, Partner partner, double lat) {
        return Store.builder().storeId(id).storeName(name).partner(partner)
                .location(new GeometryFactory().createPoint(new Coordinate(127, lat)))
                .address("서울").roadName("테헤란로").roadAddress("테헤란로 " + id).postCode("06200").build();
    }
}
