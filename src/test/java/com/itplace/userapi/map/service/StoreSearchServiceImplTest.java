package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.json.JsonData;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreSearchServiceImplTest {

    @Mock
    private ElasticsearchClient esClient;

    private StoreSearchServiceImpl service() {
        when(esClient._transportOptions()).thenReturn(new co.elastic.clients.transport.rest_client.RestClientOptions(
                org.elasticsearch.client.RequestOptions.DEFAULT));
        when(esClient.withTransportOptions(any(co.elastic.clients.transport.TransportOptions.class))).thenReturn(esClient);
        return new StoreSearchServiceImpl(esClient, 2000, 3000);
    }

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void executesBothSearchesInOneRequestAndDeduplicatesBrandHits() throws IOException {
        when(esClient.msearch(any(MsearchRequest.class), eq(JsonData.class))).thenReturn(
                co.elastic.clients.elasticsearch.core.MsearchResponse.of(m -> m.took(1)
                        .responses(result(1L, 2L), result(2L, 3L))));
        StoreSearchResult result = service().searchByKeyword("카페", "카페");
        assertThat(result.brandMatchIds()).containsExactly(1L, 2L);
        assertThat(result.nameMatchIds()).containsExactly(3L);
        var request = org.mockito.ArgumentCaptor.forClass(MsearchRequest.class);
        org.mockito.Mockito.verify(esClient).msearch(request.capture(), eq(JsonData.class));
        assertThat(request.getValue().index()).containsExactly("store");
        assertThat(request.getValue().searches()).extracting(item -> item.body().size()).containsExactly(100, 200);
        assertThat(request.getValue().searches()).allSatisfy(item ->
                assertThat(item.body().source().filter().includes()).containsExactly("storeId"));
    }

    @Test
    void subqueryFailureTriggersDatabaseFallbackInsteadOfPartialResults() throws IOException {
        when(esClient.msearch(any(MsearchRequest.class), eq(JsonData.class))).thenReturn(
                co.elastic.clients.elasticsearch.core.MsearchResponse.of(m -> m.took(1)
                        .responses(result(1L), co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem.of(i ->
                                i.failure(f -> f.status(503).error(e -> e.type("unavailable")))))));
        assertThatThrownBy(() -> service().searchByKeyword("카페", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }

    @Test
    void ignoresMissingAndInvalidSourceIds() throws IOException {
        var mapper = new co.elastic.clients.json.jackson.JacksonJsonpMapper();
        var hits = java.util.List.of(
                co.elastic.clients.elasticsearch.core.search.Hit.<JsonData>of(h -> h.index("store").id("missing")),
                co.elastic.clients.elasticsearch.core.search.Hit.<JsonData>of(h -> h.index("store").id("empty")
                        .source(JsonData.of(java.util.Map.of(), mapper))),
                co.elastic.clients.elasticsearch.core.search.Hit.<JsonData>of(h -> h.index("store").id("text")
                        .source(JsonData.of(java.util.Map.of("storeId", "invalid"), mapper))),
                co.elastic.clients.elasticsearch.core.search.Hit.<JsonData>of(h -> h.index("store").id("valid")
                        .source(JsonData.of(java.util.Map.of("storeId", 5L), mapper))));
        var response = co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem.<JsonData>of(i -> i.result(r -> r
                .took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(hits))));
        when(esClient.msearch(any(MsearchRequest.class), eq(JsonData.class))).thenReturn(
                co.elastic.clients.elasticsearch.core.MsearchResponse.of(m -> m.took(1).responses(response, result())));
        assertThat(service().searchByKeyword("카페", null).brandMatchIds()).containsExactly(5L);
    }

    private co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem<JsonData> result(Long... ids) {
        return co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem.of(i -> i.result(r -> r
                .took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(java.util.Arrays.stream(ids).map(id ->
                        co.elastic.clients.elasticsearch.core.search.Hit.<JsonData>of(hit -> hit.index("store").id(id.toString())
                                .source(JsonData.of(java.util.Map.of("storeId", id), new co.elastic.clients.json.jackson.JacksonJsonpMapper()))))
                        .toList()))));
    }

    @Test
    void searchByKeyword_doesNotInterruptRequestThreadWhenElasticsearchFails() throws IOException {
        StoreSearchServiceImpl service = service();
        when(esClient.msearch(any(MsearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("index_not_found_exception"));

        assertThatThrownBy(() -> service.searchByKeyword("스타벅스", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("매장 ES 검색 실패");

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
