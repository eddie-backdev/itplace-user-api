package com.itplace.userapi.map.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.itplace.userapi.map.dto.response.MapStorePreviewBatchResponse;
import com.itplace.userapi.map.service.ReverseGeocodeService;
import com.itplace.userapi.map.service.StoreService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoreCompactControllerTest {
    final StoreService service = mock(StoreService.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new StoreController(service, mock(ReverseGeocodeService.class)))
                .defaultRequest(get("/").accept(org.springframework.http.MediaType.APPLICATION_JSON)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nearby/previews/compact", "/nearby/category/previews/compact"})
    void nearbyAliasesReturnBatchAndForwardCoordinatesAndCategory(String path) throws Exception {
        when(service.findNearbyPreviewBatch(37.5, 127, 1000, "푸드", 0, 127.1))
                .thenReturn(MapStorePreviewBatchResponse.builder().stores(List.of()).partners(List.of()).build());
        mvc.perform(get("/api/v1/maps" + path).param("lat", "37.5").param("lng", "127")
                        .param("radiusMeters", "1000").param("category", "푸드")
                        .param("userLat", "0").param("userLng", "127.1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stores").isArray())
                .andExpect(jsonPath("$.data.partners").isArray());
        verify(service).findNearbyPreviewBatch(37.5, 127, 1000, "푸드", 0, 127.1);
    }

    @Test void keywordRouteReturnsCompactDataAndRequiresKeyword() throws Exception {
        when(service.findNearbyByKeywordPreviewBatch(37.5, 127, null, "GS25", 37.4, 127.1))
                .thenReturn(MapStorePreviewBatchResponse.builder().stores(List.of()).partners(List.of()).build());
        var request = get("/api/v1/maps/nearby/search/previews/compact")
                .param("lat", "37.5").param("lng", "127").param("userLat", "37.4").param("userLng", "127.1");
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(request.param("keyword", "GS25")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stores").isArray()).andExpect(jsonPath("$.data.partners").isArray());
        verify(service).findNearbyByKeywordPreviewBatch(37.5, 127, null, "GS25", 37.4, 127.1);
    }
}
