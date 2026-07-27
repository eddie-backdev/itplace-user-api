package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
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

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void searchByKeyword_doesNotInterruptRequestThreadWhenElasticsearchFails() throws IOException {
        StoreSearchServiceImpl service = new StoreSearchServiceImpl(esClient);
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("index_not_found_exception"));

        assertThatThrownBy(() -> service.searchByKeyword("스타벅스", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("매장 ES 검색 실패");

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
