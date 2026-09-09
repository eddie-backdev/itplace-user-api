package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.util.ObjectBuilder;
import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.service.ElasticService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BenefitRagSyncServiceTest {
    private final JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private ElasticService elasticService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private BenefitRagSourceQueryService sourceQueryService;

    private BenefitRagSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new BenefitRagSyncService(
                esClient,
                elasticService,
                embeddingService,
                sourceQueryService, new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    @Test
    void syncLookupFailureDoesNotSpendOnEmbedding() throws Exception {
        var document = activeDocument("benefit:1:policy:2:tier:3", "same-hash");
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(1,
                new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "same text")));
        when(esClient.get(anyGetRequest(), eq(JsonData.class))).thenThrow(new java.io.IOException("ES down"));
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());
        assertThat(syncService.syncAll().failedDocuments()).isEqualTo(1);
        verify(embeddingService, never()).embed(any());
        verify(esClient, never()).index(anyIndexRequest());
    }

    @Test
    void syncUpdatesMetadataWithoutReembeddingUnchangedText() throws Exception {
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "same-hash");
        BenefitDocument previous = activeDocument(document.getDocumentId(), "same-hash");
        document.setSourceUpdatedAt("2026-09-09T10:00:00");
        document.setSourceUrl("https://example.com/new");
        document.setActive(false);
        document.setSyncStatus("INACTIVE");
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(1,
                new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "same text")));
        when(esClient.get(anyGetRequest(), eq(JsonData.class))).thenReturn(currentGetResponse(previous));
        when(esClient.index(anyIndexRequest())).thenReturn(indexResponse());
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());
        assertThat(syncService.syncAll().upsertedDocuments()).isEqualTo(1);
        verify(embeddingService, never()).embed(any());
        assertThat(document.getEmbedding()).containsExactly(0.1f, 0.2f);
        assertThat(document.getDeletedAt()).isNotBlank();
    }

    @Test
    void syncAllReportsEmptyRepositoryWithoutEmbeddingWork() throws Exception {
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(0));
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        assertThat(result.scannedBenefits()).isZero();
        assertThat(result.candidateDocuments()).isZero();
        assertThat(result.upsertedDocuments()).isZero();
        assertThat(result.skippedDocuments()).isZero();
        assertThat(result.tombstonedDocuments()).isZero();
        assertThat(result.deletedDocuments()).isZero();
        assertThat(result.failedDocuments()).isZero();
        verify(embeddingService, never()).embed(any());
    }

    @Test
    void scheduledSync_doesNotTouchElasticsearchWhenSyncIsDisabled() throws Exception {
        ReflectionTestUtils.setField(syncService, "syncEnabled", false);

        syncService.scheduledSync();

        verify(elasticService, never()).createIndexIfNotExists(any());
        verify(sourceQueryService, never()).loadSourceSnapshot();
    }

    @Test
    void syncAllSkipsUnchangedContentHashEmbeddingVersionAndActiveState() throws Exception {
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-current");
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(
                1,
                new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "search text")
        ));
        when(esClient.get(anyGetRequest(), eq(JsonData.class))).thenReturn(currentGetResponse(document));
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        assertThat(result.candidateDocuments()).isEqualTo(1);
        assertThat(result.skippedDocuments()).isEqualTo(1);
        assertThat(result.upsertedDocuments()).isZero();
        verify(embeddingService, never()).embed(any());
    }

    @Test
    void syncAllUpsertsChangedContentHashWithFreshEmbedding() throws Exception {
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-new");
        BenefitDocument previous = activeDocument("benefit:1:policy:2:tier:3", "hash-old");
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(
                1,
                new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "changed text")
        ));
        when(esClient.get(anyGetRequest(), eq(JsonData.class))).thenReturn(currentGetResponse(previous));
        when(embeddingService.embed("changed text")).thenReturn(List.of(0.1f, 0.2f));
        when(esClient.index(anyIndexRequest())).thenReturn(indexResponse());
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        assertThat(result.upsertedDocuments()).isEqualTo(1);
        assertThat(result.skippedDocuments()).isZero();
        assertThat(document.getEmbedding()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void syncAllIndexesInactiveSourceAsInactiveTombstoneRepresentation() throws Exception {
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-inactive");
        document.setActive(false);
        document.setSyncStatus(BenefitRagDocumentBuilder.SYNC_STATUS_INACTIVE);
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(
                1,
                new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "inactive text")
        ));
        when(esClient.get(anyGetRequest(), eq(JsonData.class))).thenReturn(notFoundGetResponse());
        when(embeddingService.embed("inactive text")).thenReturn(List.of(0.3f));
        when(esClient.index(anyIndexRequest())).thenReturn(indexResponse());
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(emptySearchResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        assertThat(result.upsertedDocuments()).isEqualTo(1);
        assertThat(document.getActive()).isFalse();
        assertThat(document.getSyncStatus()).isEqualTo(BenefitRagDocumentBuilder.SYNC_STATUS_INACTIVE);
        assertThat(document.getDeletedAt()).isNotBlank();
    }

    @Test
    void syncAllTombstonesExistingElasticsearchDocumentWhenSourceIsMissing() throws Exception {
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(0));
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(searchResponse(mapOf(
                "documentId", "benefit:9:policy:8:tier:7",
                "benefitId", "9",
                "policyId", "8",
                "tierBenefitId", "7",
                "partnerId", "6",
                "active", true,
                "carrier", "SKT",
                "grade", "SKT_VIP",
                "isAllGrade", false,
                "onlineContext", "방문포장 25% 할인",
                "offlineContext", "매장 25% 할인"
        )));
        when(esClient.index(anyIndexRequest())).thenReturn(indexResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        ArgumentCaptor<Function<IndexRequest.Builder<BenefitDocument>, ObjectBuilder<IndexRequest<BenefitDocument>>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(esClient).index(captor.capture());
        IndexRequest<BenefitDocument> request = captor.getValue().apply(new IndexRequest.Builder<>()).build();
        BenefitDocument tombstone = request.document();
        assertThat(result.tombstonedDocuments()).isEqualTo(1);
        assertThat(result.deletedDocuments()).isZero();
        assertThat(tombstone.getDocumentId()).isEqualTo("benefit:9:policy:8:tier:7");
        assertThat(tombstone.getActive()).isFalse();
        assertThat(tombstone.getOnlineContext()).isEqualTo("방문포장 25% 할인");
        assertThat(tombstone.getOfflineContext()).isEqualTo("매장 25% 할인");
        assertThat(tombstone.getSyncStatus()).isEqualTo(BenefitRagSyncService.SYNC_STATUS_TOMBSTONED);
        assertThat(tombstone.getDeletedAt()).isNotBlank();
    }

    @Test
    void syncAllReconcilesMissingSourceDocumentsAcrossSearchAfterPages() throws Exception {
        when(sourceQueryService.loadSourceSnapshot()).thenReturn(sourceSnapshot(0));
        when(esClient.search(anySearchRequest(), eq(JsonData.class)))
                .thenReturn(searchResponseWithHits(documentSources(0, 1000)))
                .thenReturn(searchResponseWithHits(documentSources(1000, 1001)));
        when(esClient.index(anyIndexRequest())).thenReturn(indexResponse());

        BenefitRagSyncService.SyncResult result = syncService.syncAll();

        assertThat(result.tombstonedDocuments()).isEqualTo(1001);
        assertThat(result.failedDocuments()).isZero();
        verify(esClient, times(2)).search(anySearchRequest(), eq(JsonData.class));
        verify(esClient, times(1001)).index(anyIndexRequest());
    }

    private BenefitRagSourceQueryService.SourceSnapshot sourceSnapshot(
            int scannedBenefits,
            BenefitRagDocumentBuilder.PendingBenefitDocument... pendingDocuments
    ) {
        return new BenefitRagSourceQueryService.SourceSnapshot(scannedBenefits, List.of(pendingDocuments));
    }

    private BenefitDocument activeDocument(String documentId, String contentHash) {
        return BenefitDocument.builder()
                .id(documentId)
                .documentId(documentId)
                .benefitId("1")
                .policyId("2")
                .tierBenefitId("3")
                .active(true)
                .embeddingVersion(BenefitRagDocumentBuilder.EMBEDDING_VERSION)
                .contentHash(contentHash)
                .embedding(List.of(0.1f, 0.2f))
                .syncStatus(BenefitRagDocumentBuilder.SYNC_STATUS_ACTIVE)
                .build();
    }

    private GetResponse<JsonData> currentGetResponse(BenefitDocument document) {
        return GetResponse.of(g -> g
                .found(true)
                .id(document.getDocumentId())
                .index(BenefitRagSyncService.INDEX_NAME)
                .source(JsonData.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(document), jsonpMapper))
        );
    }

    private GetResponse<JsonData> notFoundGetResponse() {
        return GetResponse.of(g -> g
                .found(false)
                .id("missing")
                .index(BenefitRagSyncService.INDEX_NAME)
        );
    }

    private SearchResponse<JsonData> emptySearchResponse() {
        return SearchResponse.of(s -> s
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(h -> h.hits(List.of()))
        );
    }

    private SearchResponse<JsonData> searchResponse(Map<String, Object> source) {
        return searchResponseWithHits(List.of(source));
    }

    private SearchResponse<JsonData> searchResponseWithHits(List<Map<String, Object>> sources) {
        List<Hit<JsonData>> hits = sources.stream()
                .map(source -> {
                    String documentId = String.valueOf(source.get("documentId"));
                    return Hit.<JsonData>of(hit -> hit
                            .index(BenefitRagSyncService.INDEX_NAME)
                            .id(documentId)
                            .sort(documentId)
                            .source(jsonData(source)));
                })
                .toList();
        return SearchResponse.of(s -> s
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(h -> h.hits(hits))
        );
    }

    private List<Map<String, Object>> documentSources(int startInclusive, int endExclusive) {
        return java.util.stream.IntStream.range(startInclusive, endExclusive)
                .mapToObj(index -> Map.<String, Object>of(
                        "documentId", "benefit:%04d:policy:8:tier:7".formatted(index),
                        "benefitId", String.valueOf(index),
                        "policyId", "8",
                        "tierBenefitId", "7",
                        "partnerId", "6",
                        "active", true,
                        "carrier", "SKT",
                        "grade", "SKT_VIP",
                        "isAllGrade", false
                ))
                .toList();
    }

    private JsonData jsonData(Map<String, Object> source) {
        return JsonData.of(source, jsonpMapper);
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }

    private IndexResponse indexResponse() {
        return IndexResponse.of(i -> i
                .index(BenefitRagSyncService.INDEX_NAME)
                .id("indexed")
                .version(1)
                .result(Result.Created)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .seqNo(1)
                .primaryTerm(1)
        );
    }

    @SuppressWarnings("unchecked")
    private Function<GetRequest.Builder, ObjectBuilder<GetRequest>> anyGetRequest() {
        return any(Function.class);
    }

    @SuppressWarnings("unchecked")
    private Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> anySearchRequest() {
        return any(Function.class);
    }

    @SuppressWarnings("unchecked")
    private Function<IndexRequest.Builder<BenefitDocument>, ObjectBuilder<IndexRequest<BenefitDocument>>> anyIndexRequest() {
        return any(Function.class);
    }
}
