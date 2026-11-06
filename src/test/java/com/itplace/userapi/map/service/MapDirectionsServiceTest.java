package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoDirectionsClient;
import com.itplace.userapi.map.client.KakaoDirectionsClient.DirectionsProviderException;
import com.itplace.userapi.map.dto.MapDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse;
import com.itplace.userapi.map.dto.CoordinateDirectionsRequest;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class MapDirectionsServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private StoreRepository storeRepository;
    private KakaoDirectionsClient directionsClient;
    private MapDirectionsService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        directionsClient = mock(KakaoDirectionsClient.class);
        service = new MapDirectionsService(storeRepository, directionsClient);
        when(storeRepository.findActiveStoreWithPartner(7L)).thenReturn(Optional.of(store()));
    }

    @Test
    void parsesWalkPathCoordinatesAndSeconds() throws Exception {
        when(directionsClient.walk(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"OK","route":{"properties":{"totalDistance":1200,"totalTime":900},
                 "legs":[{"steps":[{"properties":{"guidance":"walk","distance":1200,"time":900},
                 "path":{"points":[[127.0,37.5],[127.01,37.51]]}}]}]}}
                """));

        MapDirectionsResponse response = service.directions(7L, request(MapDirectionsRequest.Mode.WALK));

        assertThat(response.routes()).hasSize(1);
        assertThat(response.routes().get(0).totalDistanceMeters()).isEqualTo(1200);
        assertThat(response.routes().get(0).totalDurationSeconds()).isEqualTo(900);
        assertThat(response.routes().get(0).segments().get(0).geometry())
                .containsExactly(new MapDirectionsResponse.Coordinate(37.5, 127.0),
                        new MapDirectionsResponse.Coordinate(37.51, 127.01));
    }

    @Test
    void keepsTransitStepGeometrySeparateAndConvertsXyToLatLng() throws Exception {
        when(directionsClient.transit(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"OK","routes":[{"properties":{"totalDistance":3100,"totalTime":1200},
                 "steps":[
                  {"properties":{"type":"WALKING","guidance":"walk","distance":100,"time":60},
                   "path":{"points":[[127.0,37.5],[127.001,37.501]]}},
                  {"properties":{"type":"BUS","guidance":"bus 10","distance":2000,"time":800},
                   "path":{"points":[[127.02,37.52],[127.021,37.521]]}},
                  {"properties":{"type":"SUBWAY","guidance":"line 2","distance":1000,"time":340},
                   "path":{"points":[[127.03,37.53],[127.031,37.531]]}}
                 ]}]}
                """));

        MapDirectionsResponse response = service.directions(7L, request(MapDirectionsRequest.Mode.TRANSIT));

        var segments = response.routes().get(0).segments();
        assertThat(segments).hasSize(3);
        assertThat(segments).extracting(MapDirectionsResponse.Segment::mode)
                .containsExactly(MapDirectionsResponse.SegmentMode.WALK,
                        MapDirectionsResponse.SegmentMode.BUS, MapDirectionsResponse.SegmentMode.SUBWAY);
        assertThat(segments.get(0).geometry().get(1)).isEqualTo(new MapDirectionsResponse.Coordinate(37.501, 127.001));
        assertThat(segments.get(1).geometry().get(0)).isEqualTo(new MapDirectionsResponse.Coordinate(37.52, 127.02));
        assertThat(segments.get(2).geometry().get(0)).isEqualTo(new MapDirectionsResponse.Coordinate(37.53, 127.03));
    }

    @Test
    void parsesCarRoadVerticesDistanceDurationAndOptionalToll() throws Exception {
        when(directionsClient.car(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"routes":[{"result_code":0,"summary":{"distance":2500,"duration":500,"fare":{"toll":0}},
                 "sections":[{"roads":[{"name":"road-a","distance":2500,"duration":500,
                 "vertexes":[127.0,37.5,127.01,37.51]}]}]}]}
                """));

        MapDirectionsResponse response = service.directions(7L, request(MapDirectionsRequest.Mode.CAR));

        var route = response.routes().get(0);
        assertThat(route.totalDistanceMeters()).isEqualTo(2500);
        assertThat(route.totalDurationSeconds()).isEqualTo(500);
        assertThat(route.tollFareWon()).isZero();
        assertThat(route.segments().get(0).geometry())
                .containsExactly(new MapDirectionsResponse.Coordinate(37.5, 127.0),
                        new MapDirectionsResponse.Coordinate(37.51, 127.01));
    }

    @Test
    void genericCoordinateDestinationReusesAllModeParsersAndHasNullStoreId() throws Exception {
        when(directionsClient.walk(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"OK","route":{"properties":{"totalDistance":100,"totalTime":80},
                 "legs":[{"steps":[{"properties":{"distance":100,"time":80},
                 "path":{"points":[[127.0,37.5],[127.01,37.51]]}}]}]}}
                """));
        when(directionsClient.transit(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"OK","routes":[{"properties":{"totalDistance":100,"totalTime":80},
                 "steps":[{"properties":{"type":"WALKING","distance":100,"time":80},
                 "path":{"points":[[127.0,37.5],[127.01,37.51]]}}]}]}
                """));
        when(directionsClient.car(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"routes":[{"result_code":0,"summary":{"distance":100,"duration":80,"fare":{"toll":0}},
                 "sections":[{"roads":[{"distance":100,"duration":80,
                 "vertexes":[127.0,37.5,127.01,37.51]}]}]}]}
                """));

        for (MapDirectionsRequest.Mode mode : MapDirectionsRequest.Mode.values()) {
            var response = service.directions(new CoordinateDirectionsRequest(mode,
                    new MapDirectionsRequest.Origin(37.5, 127.0),
                    new CoordinateDirectionsRequest.Destination(37.51, 127.01, "Selected place")));
            assertThat(response.mode()).isEqualTo(mode);
            assertThat(response.destination().storeId()).isNull();
            assertThat(response.destination().name()).isEqualTo("Selected place");
            assertThat(response.routes()).hasSize(1);
        }
    }

    @Test
    void genericDestinationWithoutNameUsesFallbackLabel() throws Exception {
        when(directionsClient.walk(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"ROUTE_RESULT_NOT_FOUND"}
                """));

        var response = service.directions(new CoordinateDirectionsRequest(MapDirectionsRequest.Mode.WALK,
                new MapDirectionsRequest.Origin(37.5, 127.0),
                new CoordinateDirectionsRequest.Destination(37.51, 127.01, "  ")));

        assertThat(response.destination().name()).isEqualTo("도착지");
        assertThat(response.destination().storeId()).isNull();
        assertThat(response.routes()).isEmpty();
    }

    @Test
    void onlyDocumentedNoRouteStatusesReturnEmptyRoutes() throws Exception {
        when(directionsClient.walk(37.5, 127.0, 37.51, 127.01))
                .thenReturn(json("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}"));
        when(directionsClient.transit(37.5, 127.0, 37.51, 127.01))
                .thenReturn(json("{\"status\":\"NO_RESULTS\",\"routes\":[]}"));
        when(directionsClient.car(37.5, 127.0, 37.51, 127.01))
                .thenReturn(json("{\"routes\":[{\"result_code\":1}]}"));

        assertThat(service.directions(7L, request(MapDirectionsRequest.Mode.WALK)).routes()).isEmpty();
        assertThat(service.directions(7L, request(MapDirectionsRequest.Mode.TRANSIT)).routes()).isEmpty();
        assertThat(service.directions(7L, request(MapDirectionsRequest.Mode.CAR)).routes()).isEmpty();
    }

    @Test
    void successWithoutUsableGeometryIsProviderError() throws Exception {
        when(directionsClient.transit(37.5, 127.0, 37.51, 127.01)).thenReturn(json("""
                {"status":"OK","routes":[{"properties":{"totalDistance":0,"totalTime":0},"steps":[]}]}
                """));

        assertThatThrownBy(() -> service.directions(7L, request(MapDirectionsRequest.Mode.TRANSIT)))
                .isInstanceOf(DirectionsProviderException.class)
                .extracting(error -> ((DirectionsProviderException) error).getCode())
                .isEqualTo(StoreCode.DIRECTIONS_PROVIDER_ERROR);
    }

    @Test
    void inactiveStoreIsNotFound() {
        when(storeRepository.findActiveStoreWithPartner(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.directions(8L, request(MapDirectionsRequest.Mode.WALK)))
                .isInstanceOf(DirectionsProviderException.class)
                .extracting(error -> ((DirectionsProviderException) error).getCode())
                .isEqualTo(StoreCode.STORE_NOT_FOUND);
    }

    private MapDirectionsRequest request(MapDirectionsRequest.Mode mode) {
        return new MapDirectionsRequest(mode, new MapDirectionsRequest.Origin(37.5, 127.0));
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    private Store store() {
        return Store.builder()
                .storeId(7L)
                .storeName("Test store")
                .latitude(BigDecimal.valueOf(37.51))
                .longitude(BigDecimal.valueOf(127.01))
                .location(new GeometryFactory().createPoint(new Coordinate(127.01, 37.51)))
                .active(true)
                .build();
    }
}
