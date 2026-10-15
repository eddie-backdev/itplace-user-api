package com.itplace.userapi.map.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.itplace.userapi.map.dto.response.MapStorePreviewBatchResponse;
import com.itplace.userapi.map.dto.response.MapStorePreviewPageResponse;
import com.itplace.userapi.map.service.ReverseGeocodeService;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import java.util.stream.Stream;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;

class StoreCompactControllerTest {
    final StoreService service = mock(StoreService.class);
    MockMvc mvc;
    ValidatorFactory validatorFactory;

    @BeforeEach void setup() {
        clearInvocations(service);
        StoreController controller = new StoreController(service, mock(ReverseGeocodeService.class));
        validatorFactory = Validation.buildDefaultValidatorFactory();
        MethodValidationPostProcessor validation = new MethodValidationPostProcessor();
        validation.setValidator(validatorFactory.getValidator());
        validation.afterPropertiesSet();
        Object validatedController = validation.postProcessAfterInitialization(controller, "storeController");
        mvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(org.springframework.http.MediaType.APPLICATION_JSON)).build();
    }

    @AfterEach void closeValidator() { validatorFactory.close(); }

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

    @ParameterizedTest
    @ValueSource(strings = {"/nearby/previews/compact/page", "/nearby/category/previews/compact/page"})
    void nearbyPageAliasesForwardCursorAndAreNotCacheable(String path) throws Exception {
        when(service.findNearbyPreviewPage(37.5,127,1000,"푸드",0,127.1,2,10))
                .thenReturn(MapStorePreviewPageResponse.builder().stores(List.of()).partners(List.of())
                        .hasMore(false).nextCursor(null).build());
        mvc.perform(get("/api/v1/maps" + path).param("lat","37.5").param("lng","127")
                        .param("radiusMeters","1000").param("category","푸드").param("userLat","0")
                        .param("userLng","127.1").param("pageSize","2").param("afterStoreId","10"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.hasMore").value(false)).andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        verify(service).findNearbyPreviewPage(37.5,127,1000,"푸드",0,127.1,2,10);
    }

    @Test
    void viewportPageDefaultsTo300AndZeroCursor() throws Exception {
        when(service.findStoresInViewPreviewPage(37.49,126.99,37.51,127.01,null,300,0))
                .thenReturn(MapStorePreviewPageResponse.builder().stores(List.of()).partners(List.of())
                        .hasMore(false).nextCursor(null).build());
        mvc.perform(get("/api/v1/maps/stores/in-view/previews/compact/page")
                        .param("minLat","37.49").param("minLng","126.99")
                        .param("maxLat","37.51").param("maxLng","127.01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        verify(service).findStoresInViewPreviewPage(37.49,126.99,37.51,127.01,null,300,0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2001", "-1"})
    void pageRoutesRejectInvalidPageSizeOrCursorBeforeService(String invalid) throws Exception {
        var base = get("/api/v1/maps/nearby/previews/compact/page")
                .param("lat","37.5").param("lng","127").param("radiusMeters","1000")
                .param("userLat","37.5").param("userLng","127");
        if ("-1".equals(invalid)) base.param("afterStoreId", invalid);
        else base.param("pageSize", invalid);
        mvc.perform(base).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
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

    @ParameterizedTest
    @MethodSource("invalidNearbyCoordinatesAndRadius")
    void nearbyRejectsOutOfRangeOrMalformedNumbersBeforeService(String lat, String lng, String radius) throws Exception {
        mvc.perform(get("/api/v1/maps/nearby/previews/compact")
                        .param("lat", lat).param("lng", lng).param("radiusMeters", radius)
                        .param("userLat", "37.5").param("userLng", "127"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidNearbyCoordinatesAndRadius() {
        return Stream.of(arguments("91", "127", "1000"), arguments("37.5", "181", "1000"),
                arguments("37.5", "127", "0"), arguments("37.5", "127", "400001"),
                arguments("NaN", "127", "1000"), arguments("north", "127", "1000"));
    }
}
