package com.itplace.userapi.map.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.common.GlobalExceptionHandler;
import com.itplace.userapi.map.controller.StoreController;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class StoreSeoContractTest {
    @Mock private StoreRepository storeRepository;
    @Mock private PartnerRepository partnerRepository;
    @Mock private PartnerBenefitCacheService partnerBenefitCacheService;
    @Mock private StoreSearchService storeSearchService;
    @Mock private StoreClusterCacheService storeClusterCacheService;
    @Mock private StoreClusterQueryService storeClusterQueryService;
    @Mock private MapClusterSnapshotCache mapClusterSnapshotCache;
    @Mock private StorePreviewQueryService storePreviewQueryService;
    @InjectMocks private StoreServiceImpl storeService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StoreController(storeService, mock(ReverseGeocodeService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void activeStoreReturnsLocationAndPartnerFacts() throws Exception {
        Partner partner = Partner.builder().partnerId(3L).partnerName("예시 브랜드").category("FOOD").build();
        Store store = Store.builder()
                .storeId(42L)
                .storeName("예시 강남점")
                .partner(partner)
                .address("서울 강남구 테헤란로 1")
                .roadAddress("서울 강남구 테헤란로 1")
                .location(new GeometryFactory().createPoint(new Coordinate(127.0276, 37.4979)))
                .build();
        when(storeRepository.findActiveStoreWithPartner(42L)).thenReturn(Optional.of(store));

        mvc.perform(get("/api/v1/maps/stores/42").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store.storeId").value(42))
                .andExpect(jsonPath("$.data.store.storeName").value("예시 강남점"))
                .andExpect(jsonPath("$.data.store.latitude").value(37.4979))
                .andExpect(jsonPath("$.data.store.longitude").value(127.0276))
                .andExpect(jsonPath("$.data.partner.partnerId").value(3))
                .andExpect(jsonPath("$.data.partner.partnerName").value("예시 브랜드"))
                .andExpect(jsonPath("$.data.tierBenefit").doesNotExist());
        verify(storeRepository).findActiveStoreWithPartner(42L);
    }

    @Test
    void missingStoreReturnsNotFound() throws Exception {
        when(storeRepository.findActiveStoreWithPartner(404L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/maps/stores/404").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        verify(storeRepository).findActiveStoreWithPartner(404L);
    }

    @Test
    void inactiveStoreReturnsNotFound() throws Exception {
        // The repository's active=true predicate makes inactive rows indistinguishable from missing rows.
        when(storeRepository.findActiveStoreWithPartner(405L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/maps/stores/405").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        verify(storeRepository).findActiveStoreWithPartner(405L);
    }
}
