package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.ai.rag.postgres.BenefitSearchHit;
import com.itplace.userapi.ai.rag.postgres.PostgresBenefitSearchRepository;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BenefitHybridSearchServiceImplTest {
    private final JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private PostgresBenefitSearchRepository postgresSearchRepository;

    @InjectMocks
    private BenefitHybridSearchServiceImpl service;

    @BeforeEach
    void enableAiForHybridSearchTests() {
        ReflectionTestUtils.setField(service, "aiEnabled", true);
    }

    @Test
    void disabledAiSearchReturnsKeywordResultsWithoutEmbedding() throws IOException {
        ReflectionTestUtils.setField(service, "aiEnabled", false);
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(hit(1L, 2.0)));

        BenefitHybridSearchResult result = service.search("커피", null, null, null, List.of(), PageRequest.of(0, 12));

        assertThat(result.benefitIds()).containsExactly(1L);
        verifyNoInteractions(embeddingService);
        verify(esClient).search(any(SearchRequest.class), eq(JsonData.class));
    }

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
        when(embeddingService.embedQuery("커피")).thenReturn(List.of(0.1f, 0.2f));

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
        verify(embeddingService).embedQuery("커피");
    }

    @Test
    void search_degradesToLexicalResultsWhenEmbeddingFails() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenReturn(searchResponse(hit(1L, 2.0)));
        when(embeddingService.embedQuery("커피")).thenThrow(new IllegalStateException("embedding unavailable"));

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
    void searchUsesVectorResultsWhenLexicalProviderThrowsIOException() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("lexical unavailable"))
                .thenReturn(searchResponse(hit(8L, 1.0)));
        when(embeddingService.embedQuery("커피")).thenReturn(List.of(0.1f));

        BenefitHybridSearchResult result = service.search("커피", null, null, null, List.of(), PageRequest.of(0, 5));

        assertThat(result.benefitIds()).containsExactly(8L);
        verify(esClient, org.mockito.Mockito.times(2)).search(any(SearchRequest.class), eq(JsonData.class));
    }

    @Test
    void searchUsesVectorResultsWhenLexicalProviderThrowsRuntimeFailure() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IllegalStateException("lexical unavailable"))
                .thenReturn(searchResponse(hit(8L, 1.0)));
        when(embeddingService.embedQuery("커피")).thenReturn(List.of(0.1f));

        BenefitHybridSearchResult result = service.search("커피", null, null, null, List.of(), PageRequest.of(0, 5));

        assertThat(result.benefitIds()).containsExactly(8L);
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
        verify(embeddingService, never()).embedQuery(any());
    }

    @Test
    void search_throwsWhenBothLexicalAndVectorSearchAreUnavailable() throws IOException {
        when(esClient.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("es unavailable"));
        when(embeddingService.embedQuery("커피")).thenThrow(new IllegalStateException("embedding unavailable"));

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

    @Test
    void postgresModeUsesRepositoryAndNeverCallsElasticsearch() {
        usePostgres();
        when(embeddingService.embedQuery("커피")).thenReturn(List.of(0.1f));
        when(postgresSearchRepository.searchList("커피", List.of(0.1f), MainCategory.BASIC_BENEFIT,
                "카페", UsageType.OFFLINE, List.of(Carrier.LGU), 80))
                .thenReturn(List.of(postgresHit(2L), postgresHit(1L)));

        BenefitHybridSearchResult result = service.search(" 커피 ", MainCategory.BASIC_BENEFIT, "카페",
                UsageType.OFFLINE, List.of(Carrier.LGU), PageRequest.of(0, 12));

        assertThat(result.benefitIds()).containsExactly(2L, 1L);
        verify(postgresSearchRepository).searchList("커피", List.of(0.1f), MainCategory.BASIC_BENEFIT,
                "카페", UsageType.OFFLINE, List.of(Carrier.LGU), 80);
        verifyNoInteractions(esClient);
    }

    @Test
    void postgresModeWithAiDisabledUsesLexicalSearchWithoutEmbedding() {
        usePostgres();
        ReflectionTestUtils.setField(service, "aiEnabled", false);
        when(postgresSearchRepository.searchList("커피", null, null, null, null, List.of(), 80))
                .thenReturn(List.of(postgresHit(9L)));

        BenefitHybridSearchResult result = service.search("커피", null, null, null, List.of(), PageRequest.of(0, 12));

        assertThat(result.benefitIds()).containsExactly(9L);
        verifyNoInteractions(embeddingService, esClient);
        verify(postgresSearchRepository).searchList("커피", null, null, null, null, List.of(), 80);
    }

    @Test
    void postgresModeFallsBackToLexicalWhenEmbeddingFailsAndForwardsFilters() {
        usePostgres();
        when(embeddingService.embedQuery("커피")).thenThrow(new IllegalStateException("embedding unavailable"));
        when(postgresSearchRepository.searchList("커피", null, MainCategory.VIP_COCK, "카페",
                UsageType.ONLINE, List.of(Carrier.KT), 80)).thenReturn(List.of(postgresHit(4L)));

        BenefitHybridSearchResult result = service.search("커피", MainCategory.VIP_COCK, "카페",
                UsageType.ONLINE, List.of(Carrier.KT), PageRequest.of(0, 12));

        assertThat(result.benefitIds()).containsExactly(4L);
        verify(postgresSearchRepository).searchList("커피", null, MainCategory.VIP_COCK, "카페",
                UsageType.ONLINE, List.of(Carrier.KT), 80);
        verifyNoInteractions(esClient);
    }

    @Test
    void postgresModeFallsBackToLexicalWhenVectorSearchFails() {
        usePostgres();
        List<Float> vector = List.of(0.2f);
        when(embeddingService.embedQuery("커피")).thenReturn(vector);
        when(postgresSearchRepository.searchList("커피", vector, null, null, null, List.of(), 80))
                .thenThrow(new IllegalArgumentException("invalid vector"));
        when(postgresSearchRepository.searchList("커피", null, null, null, null, List.of(), 80))
                .thenReturn(List.of(postgresHit(7L)));

        BenefitHybridSearchResult result = service.search("커피", null, null, null, List.of(), PageRequest.of(0, 12));

        assertThat(result.benefitIds()).containsExactly(7L);
        verify(postgresSearchRepository).searchList("커피", null, null, null, null, List.of(), 80);
        verifyNoInteractions(esClient);
    }

    private void usePostgres() {
        service.setPostgresSearchRepository(postgresSearchRepository);
        service.setSearchProvider("postgres");
    }

    private BenefitSearchHit postgresHit(long benefitId) {
        return new BenefitSearchHit(benefitId, benefitId + 100, null, 1.0, null);
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
