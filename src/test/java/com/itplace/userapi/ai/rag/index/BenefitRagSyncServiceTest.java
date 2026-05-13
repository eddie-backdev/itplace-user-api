package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitRagSyncServiceTest {
    private final JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper();

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private ElasticService elasticService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private BenefitRagDocumentBuilder documentBuilder;

    private BenefitRagSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new BenefitRagSyncService(
                esClient,
                benefitRepository,
                elasticService,
                embeddingService,
                documentBuilder
        );
    }

    @Test
    void syncAllReportsEmptyRepositoryWithoutEmbeddingWork() throws Exception {
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of());
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
    void syncAllSkipsUnchangedContentHashEmbeddingVersionAndActiveState() throws Exception {
        Benefit benefit = benefit(1L);
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-current");
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(documentBuilder.buildPendingDocuments(benefit))
                .thenReturn(List.of(new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "search text")));
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
        Benefit benefit = benefit(1L);
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-new");
        BenefitDocument previous = activeDocument("benefit:1:policy:2:tier:3", "hash-old");
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(documentBuilder.buildPendingDocuments(benefit))
                .thenReturn(List.of(new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "changed text")));
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
        Benefit benefit = benefit(1L);
        BenefitDocument document = activeDocument("benefit:1:policy:2:tier:3", "hash-inactive");
        document.setActive(false);
        document.setSyncStatus(BenefitRagDocumentBuilder.SYNC_STATUS_INACTIVE);
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(benefit));
        when(documentBuilder.buildPendingDocuments(benefit))
                .thenReturn(List.of(new BenefitRagDocumentBuilder.PendingBenefitDocument(document, "inactive text")));
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
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of());
        when(esClient.search(anySearchRequest(), eq(JsonData.class))).thenReturn(searchResponse(Map.of(
                "documentId", "benefit:9:policy:8:tier:7",
                "benefitId", "9",
                "policyId", "8",
                "tierBenefitId", "7",
                "partnerId", "6",
                "active", true,
                "carrier", "SKT",
                "grade", "SKT_VIP",
                "isAllGrade", false
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
        assertThat(tombstone.getSyncStatus()).isEqualTo(BenefitRagSyncService.SYNC_STATUS_TOMBSTONED);
        assertThat(tombstone.getDeletedAt()).isNotBlank();
    }

    private Benefit benefit(Long id) {
        return Benefit.builder().benefitId(id).active(true).build();
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
                .syncStatus(BenefitRagDocumentBuilder.SYNC_STATUS_ACTIVE)
                .build();
    }

    private GetResponse<JsonData> currentGetResponse(BenefitDocument document) {
        return GetResponse.of(g -> g
                .found(true)
                .id(document.getDocumentId())
                .index(BenefitRagSyncService.INDEX_NAME)
                .source(jsonData(Map.of(
                        "documentId", document.getDocumentId(),
                        "contentHash", document.getContentHash(),
                        "embeddingVersion", document.getEmbeddingVersion(),
                        "active", document.getActive()
                )))
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
        return SearchResponse.of(s -> s
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(h -> h.hits(Hit.of(hit -> hit
                        .index(BenefitRagSyncService.INDEX_NAME)
                        .id(String.valueOf(source.get("documentId")))
                        .source(jsonData(source))
                )))
        );
    }

    private JsonData jsonData(Map<String, Object> source) {
        return JsonData.of(source, jsonpMapper);
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
