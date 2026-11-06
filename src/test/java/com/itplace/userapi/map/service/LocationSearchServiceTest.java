package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoLocalSearchClient;
import com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException;
import com.itplace.userapi.map.dto.LocationSearchRequest;
import com.itplace.userapi.map.dto.LocationSearchResponse.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocationSearchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private KakaoLocalSearchClient client;
    private LocationSearchService service;

    @BeforeEach
    void setUp() {
        client = mock(KakaoLocalSearchClient.class);
        service = new LocationSearchService(client);
    }

    @Test
    void mapsAddressAndPlaceCoordinatesAndKeepsDifferentStoresAtSameCoordinates() throws Exception {
        when(client.address("서울역")).thenReturn(json("""
                {"documents":[{"address_name":"서울 중구 한강대로 405","x":"126.9721","y":"37.5547",
                 "address":{"address_name":"서울 중구 봉래동2가 122"},
                 "road_address":{"address_name":"서울 중구 한강대로 405"}}]}
                """));
        when(client.places("서울역", 37.5, 127.0)).thenReturn(json("""
                {"documents":[
                  {"id":"1","place_name":"서울역","address_name":"서울 중구 봉래동2가 122",
                   "road_address_name":"서울 중구 한강대로 405","x":"126.9721","y":"37.5547","distance":"50"},
                  {"id":"2","place_name":"서울역 1번 출구","address_name":"서울 중구 봉래동2가 122",
                   "road_address_name":"서울 중구 한강대로 405","x":"126.9721","y":"37.5547","distance":"55"}
                ]}
                """));

        var result = service.search(new LocationSearchRequest(" 서울역 ", new LocationSearchRequest.Center(37.5, 127.0)));

        assertThat(result.partial()).isFalse();
        assertThat(result.locations()).hasSize(3);
        assertThat(result.locations()).extracting("name")
                .containsExactly("서울역", "서울 중구 한강대로 405", "서울역 1번 출구");
        assertThat(result.locations().get(0).kind()).isEqualTo(Kind.PLACE);
        assertThat(result.locations().get(0).lat()).isEqualTo(37.5547);
        assertThat(result.locations().get(0).lng()).isEqualTo(126.9721);
        assertThat(result.locations().get(0).distanceMeters()).isEqualTo(50);
    }

    @Test
    void returnsSuccessfulSideAsPartialEvenWhenItHasNoCandidates() throws Exception {
        when(client.address("주소검색")).thenReturn(json("{\"documents\":[]}"));
        when(client.places("주소검색", null, null))
                .thenThrow(new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT));

        var result = service.search(new LocationSearchRequest("주소검색", null));

        assertThat(result.locations()).isEmpty();
        assertThat(result.partial()).isTrue();
    }

    @Test
    void emptySourcesAreNormalAndDedupeNeedsBothSameNameAndSameCoordinates() throws Exception {
        when(client.address("플라자")).thenReturn(json("""
                {"documents":[{"address_name":"플라자","x":"127.0","y":"37.5"}]}
                """));
        when(client.places("플라자", null, null)).thenReturn(json("""
                {"documents":[
                  {"id":"1","place_name":"플라자","x":"127.0","y":"37.5"},
                  {"id":"2","place_name":"플라자","x":"127.01","y":"37.5"},
                  {"id":"3","place_name":"플라자 서관","x":"127.0","y":"37.5"}
                ]}
                """));

        var result = service.search(new LocationSearchRequest("플라자", null));

        assertThat(result.partial()).isFalse();
        assertThat(result.locations()).hasSize(3);
        assertThat(result.locations()).extracting("lat").containsOnly(37.5);
        assertThat(result.locations()).extracting("lng").contains(127.0, 127.01);
        assertThat(result.locations()).extracting("name").containsExactly("플라자", "플라자", "플라자 서관");
    }

    @Test
    void twoSuccessfulEmptyResponsesReturnConfirmedEmptyList() throws Exception {
        when(client.address("없는후보")).thenReturn(json("{\"documents\":[]}"));
        when(client.places("없는후보", null, null)).thenReturn(json("{\"documents\":[]}"));

        var result = service.search(new LocationSearchRequest("없는후보", null));

        assertThat(result.locations()).isEmpty();
        assertThat(result.partial()).isFalse();
    }

    @Test
    void candidateNameFitsGenericDirectionsInputLimit() throws Exception {
        when(client.address("긴장소이름")).thenReturn(json("{\"documents\":[]}"));
        when(client.places("긴장소이름", null, null)).thenReturn(json("""
                {"documents":[{"id":"long","place_name":"%s","x":"127.0","y":"37.5"}]}
                """.formatted("가".repeat(99) + "😀" + "나")));

        var result = service.search(new LocationSearchRequest("긴장소이름", null));

        assertThat(result.locations()).hasSize(1);
        assertThat(result.locations().get(0).name().length()).isLessThanOrEqualTo(100);
        String name = result.locations().get(0).name();
        assertThat(Character.isHighSurrogate(name.charAt(name.length() - 1))).isFalse();
    }

    @Test
    void bothFailuresReturnUnavailableBeforeTimeoutAndProviderError() {
        when(client.address("cafe"))
                .thenThrow(new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR));
        when(client.places("cafe", null, null))
                .thenThrow(new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE));

        assertThatThrownBy(() -> service.search(new LocationSearchRequest("cafe", null)))
                .isInstanceOf(LocalSearchException.class)
                .extracting(error -> ((LocalSearchException) error).getCode())
                .isEqualTo(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
    }

    @Test
    void mergesAtMostTenAndOnlyDeduplicatesSameNameAndCoordinates() throws Exception {
        StringBuilder addressDocs = new StringBuilder("{\"documents\":[");
        StringBuilder placeDocs = new StringBuilder("{\"documents\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) { addressDocs.append(','); placeDocs.append(','); }
            addressDocs.append("{\"address_name\":\"A").append(i)
                    .append("\",\"x\":\"127.0\",\"y\":\"37.5\"}");
            placeDocs.append("{\"id\":\"").append(i).append("\",\"place_name\":\"P")
                    .append(i).append("\",\"x\":\"127.0\",\"y\":\"37.5\"}");
        }
        addressDocs.append("]}"); placeDocs.append("]}");
        when(client.address("서울 강남구")).thenReturn(json(addressDocs.toString()));
        when(client.places("서울 강남구", null, null)).thenReturn(json(placeDocs.toString()));

        var result = service.search(new LocationSearchRequest("서울 강남구", null));

        assertThat(result.locations()).hasSize(10);
        assertThat(result.locations()).extracting("kind").contains(Kind.ADDRESS, Kind.PLACE);
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
