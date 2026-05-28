package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class BenefitHybridSearchServiceImplTest {
    private final JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private EmbeddingService embeddingService;

    @InjectMocks
    private BenefitHybridSearchServiceImpl service;

    @Test
    void search_mergesLexicalAndVectorResultsAndPagesRankedBenefitIds() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(
                        hit(1L, 2.0),
                        hit(2L, 1.5)
                ))
                .thenReturn(searchResponse(
                        hit(2L, 0.92),
                        hit(3L, 0.88)
                ));
        when(embeddingService.embed("커피")).thenReturn(List.of(0.1f, 0.2f));

        BenefitHybridSearchResult result = service.search(
                "  커피  ",
                MainCategory.BASIC_BENEFIT,
                "카페",
                UsageType.OFFLINE,
                List.of(Carrier.LGU),
                PageRequest.of(0, 2)
        );

        assertThat(result.benefitIds()).containsExactly(2L, 1L);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        verify(embeddingService).embed("커피");
    }

    @Test
    void search_degradesToLexicalResultsWhenEmbeddingFails() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(hit(1L, 2.0)));
        when(embeddingService.embed("커피")).thenThrow(new IllegalStateException("embedding unavailable"));

        BenefitHybridSearchResult result = service.search(
                "커피",
                null,
                null,
                null,
                List.of(),
                PageRequest.of(0, 12)
        );

        assertThat(result.benefitIds()).containsExactly(1L);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void searchLexical_usesOnlyLexicalSearchAndPagesBenefitIds() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(
                        hit(1L, 2.0),
                        hit(2L, 1.5)
                ));

        BenefitHybridSearchResult result = service.searchLexical(
                "커피",
                null,
                null,
                null,
                List.of(),
                PageRequest.of(0, 1)
        );

        assertThat(result.benefitIds()).containsExactly(1L);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        verify(embeddingService, never()).embed(any());
    }

    @Test
    void search_throwsWhenBothLexicalAndVectorSearchAreUnavailable() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("es unavailable"));
        when(embeddingService.embed("커피")).thenThrow(new IllegalStateException("embedding unavailable"));

        assertThatThrownBy(() -> service.search(
                "커피",
                null,
                null,
                null,
                List.of(),
                PageRequest.of(0, 12)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("혜택 하이브리드 검색을 사용할 수 없습니다");
    }

    private SearchResponse<JsonData> searchResponse(Map<String, Object>... sources) {
        List<Hit<JsonData>> hits = java.util.Arrays.stream(sources)
                .map(source -> Hit.<JsonData>of(hit -> hit
                        .index("benefit")
                        .id(String.valueOf(source.get("benefitId")))
                        .score(((Number) source.get("score")).doubleValue())
                        .source(jsonData(source))))
                .toList();
        return SearchResponse.of(s -> s
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(h -> h.hits(hits))
        );
    }

    private Map<String, Object> hit(Long benefitId, double score) {
        return Map.of("benefitId", benefitId, "score", score);
    }

    private JsonData jsonData(Map<String, Object> source) {
        return JsonData.of(source, jsonpMapper);
    }
}
