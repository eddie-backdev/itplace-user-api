package com.itplace.userapi.ai.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.service.ElasticService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitRagSyncService {
    static final String INDEX_NAME = "benefit";
    static final String SYNC_STATUS_TOMBSTONED = "TOMBSTONED";
    private static final int RECONCILIATION_PAGE_SIZE = 1000;

    private final ElasticsearchClient esClient;
    private final ElasticService elasticService;
    private final EmbeddingService embeddingService;
    private final BenefitRagSourceQueryService sourceQueryService;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.benefits.sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${app.ai.benefits.sync.delay-ms:0}")
    private long delayMs;

    @Scheduled(
            fixedDelayString = "${app.ai.benefits.sync.fixed-delay-ms:3600000}",
            initialDelayString = "${app.ai.benefits.sync.initial-delay-ms:300000}"
    )
    public void scheduledSync() {
        if (!syncEnabled) {
            log.debug("혜택 RAG 주기 동기화가 비활성화되어 있습니다.");
            return;
        }
        try {
            SyncResult result = syncAll();
            log.info("혜택 RAG 주기 동기화 완료: {}", result);
        } catch (Exception e) {
            log.warn("혜택 RAG 주기 동기화 실패: {}", e.getMessage());
        }
    }

    public SyncResult syncAll() throws Exception {
        elasticService.createIndexIfNotExists(INDEX_NAME);

        BenefitRagSourceQueryService.SourceSnapshot sourceSnapshot = sourceQueryService.loadSourceSnapshot();
        int scannedBenefits = sourceSnapshot.scannedBenefits();
        int candidateDocuments = 0;
        int upsertedDocuments = 0;
        int skippedDocuments = 0;
        int tombstonedDocuments = 0;
        int deletedDocuments = 0;
        int failedDocuments = 0;
        Set<String> currentDocumentIds = new LinkedHashSet<>();

        for (BenefitRagDocumentBuilder.PendingBenefitDocument pending : sourceSnapshot.pendingDocuments()) {
            candidateDocuments++;
            BenefitDocument document = pending.document();
            currentDocumentIds.add(document.getDocumentId());
            try {
                JsonNode existing = loadExisting(document);
                List<Float> embedding = reusableEmbedding(document, existing);
                if (!embedding.isEmpty() && isCurrent(document, existing)) {
                    skippedDocuments++;
                    continue;
                }

                BenefitDocument upsertDocument = pending.withEmbedding(
                        embedding.isEmpty() ? embeddingService.embed(pending.searchableText()) : embedding);
                esClient.index(i -> i
                        .index(INDEX_NAME)
                        .id(upsertDocument.getDocumentId())
                        .document(upsertDocument)
                );
                upsertedDocuments++;

                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (Exception e) {
                failedDocuments++;
                log.warn("혜택 RAG 문서 동기화 실패: documentId={}, reason={}", document.getDocumentId(), e.getMessage());
            }
        }

        ReconciliationResult reconciliationResult = reconcileMissingSourceDocuments(currentDocumentIds);
        tombstonedDocuments += reconciliationResult.tombstonedDocuments();
        deletedDocuments += reconciliationResult.deletedDocuments();
        failedDocuments += reconciliationResult.failedDocuments();

        return new SyncResult(
                scannedBenefits,
                candidateDocuments,
                upsertedDocuments,
                skippedDocuments,
                tombstonedDocuments,
                deletedDocuments,
                failedDocuments
        );
    }

    private ReconciliationResult reconcileMissingSourceDocuments(Set<String> currentDocumentIds) {
        int tombstonedDocuments = 0;
        int deletedDocuments = 0;
        int failedDocuments = 0;

        try {
            List<FieldValue> searchAfter = null;
            while (true) {
                List<FieldValue> pageSearchAfter = searchAfter;
                SearchResponse<JsonData> response = esClient.search(s -> {
                            var builder = s
                                    .index(INDEX_NAME)
                                    .size(RECONCILIATION_PAGE_SIZE)
                                    .query(q -> q.matchAll(m -> m))
                                    .sort(sort -> sort.field(f -> f.field("documentId").order(SortOrder.Asc)));
                            if (pageSearchAfter != null && !pageSearchAfter.isEmpty()) {
                                builder.searchAfter(pageSearchAfter);
                            }
                            return builder;
                        },
                        JsonData.class
                );
                if (response == null || response.hits() == null || response.hits().hits().isEmpty()) {
                    break;
                }

                List<Hit<JsonData>> hits = response.hits().hits();
                for (Hit<JsonData> hit : hits) {
                    Optional<ExistingDocumentSnapshot> snapshot = toExistingDocumentSnapshot(hit);
                    if (snapshot.isEmpty()) {
                        continue;
                    }
                    ExistingDocumentSnapshot existing = snapshot.get();
                    if (currentDocumentIds.contains(existing.documentId()) || !existing.active()) {
                        continue;
                    }

                    BenefitDocument tombstone = tombstoneDocument(existing);
                    esClient.index(i -> i
                            .index(INDEX_NAME)
                            .id(tombstone.getDocumentId())
                            .document(tombstone)
                    );
                    tombstonedDocuments++;
                }

                if (hits.size() < RECONCILIATION_PAGE_SIZE || hits.get(hits.size() - 1).sort().isEmpty()) {
                    break;
                }
                searchAfter = hits.get(hits.size() - 1).sort();
            }
        } catch (Exception e) {
            failedDocuments++;
            log.warn("혜택 RAG 누락 원천 문서 reconciliation 실패: {}", e.getMessage());
        }

        return new ReconciliationResult(tombstonedDocuments, deletedDocuments, failedDocuments);
    }

    private Optional<ExistingDocumentSnapshot> toExistingDocumentSnapshot(Hit<JsonData> hit) {
        try {
            JsonNode node = hit.source().to(JsonNode.class);
            String documentId = textOrBlank(node, "documentId");
            if (documentId.isBlank()) {
                documentId = hit.id();
            }
            if (documentId == null || documentId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ExistingDocumentSnapshot(documentId, node, booleanOrDefault(node, "active", true)));
        } catch (RuntimeException e) {
            log.debug("혜택 RAG 기존 문서 snapshot 파싱 실패로 reconciliation에서 제외합니다. id={}, reason={}",
                    hit.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private BenefitDocument tombstoneDocument(ExistingDocumentSnapshot existing) {
        JsonNode node = existing.node();
        String now = Instant.now().toString();
        return BenefitDocument.builder()
                .id(existing.documentId())
                .documentId(existing.documentId())
                .partnerId(textOrBlank(node, "partnerId"))
                .partnerName(textOrBlank(node, "partnerName"))
                .benefitId(textOrBlank(node, "benefitId"))
                .benefitName(textOrBlank(node, "benefitName"))
                .policyId(textOrBlank(node, "policyId"))
                .tierBenefitId(blankToNull(textOrBlank(node, "tierBenefitId")))
                .carrierBenefitName(textOrBlank(node, "carrierBenefitName"))
                .category(textOrBlank(node, "category"))
                .mainCategory(textOrBlank(node, "mainCategory"))
                .carrier(textOrBlank(node, "carrier"))
                .grade(blankToNull(textOrBlank(node, "grade")))
                .isAllGrade(booleanOrDefault(node, "isAllGrade", false))
                .usageType(textOrBlank(node, "usageType"))
                .benefitType(textOrBlank(node, "benefitType"))
                .active(false)
                .sourceKey(textOrBlank(node, "sourceKey"))
                .sourceUrl(textOrBlank(node, "sourceUrl"))
                .sourceCategory(textOrBlank(node, "sourceCategory"))
                .lastCrawledAt(blankToNull(textOrBlank(node, "lastCrawledAt")))
                .indexedAt(now)
                .sourceUpdatedAt(blankToNull(textOrBlank(node, "sourceUpdatedAt")))
                .embeddingVersion(textOrBlank(node, "embeddingVersion"))
                .contentHash(textOrBlank(node, "contentHash"))
                .syncStatus(SYNC_STATUS_TOMBSTONED)
                .deletedAt(now)
                .description(textOrBlank(node, "description"))
                .manual(textOrBlank(node, "manual"))
                .context(textOrBlank(node, "context"))
                .tierContext(textOrBlank(node, "tierContext"))
                .onlineContext(blankToNull(textOrBlank(node, "onlineContext")))
                .offlineContext(blankToNull(textOrBlank(node, "offlineContext")))
                .discountValue(integerOrNull(node, "discountValue"))
                .businessType(textOrBlank(node, "businessType"))
                .useCases(textList(node, "useCases"))
                .negativeUseCases(textList(node, "negativeUseCases"))
                .tags(textList(node, "tags"))
                .build();
    }

    private JsonNode loadExisting(BenefitDocument document) throws IOException {
        GetResponse<JsonData> response = esClient.get(g -> g.index(INDEX_NAME).id(document.getDocumentId()), JsonData.class);
        return response.found() && response.source() != null ? response.source().to(JsonNode.class) : null;
    }

    private List<Float> reusableEmbedding(BenefitDocument document, JsonNode existing) {
        if (existing == null || !textOrBlank(existing, "contentHash").equals(document.getContentHash())
                || !textOrBlank(existing, "embeddingVersion").equals(document.getEmbeddingVersion())) {
            return List.of();
        }
        JsonNode vector = existing.path("embedding");
        if (!vector.isArray() || vector.isEmpty()) {
            return List.of();
        }
        List<Float> values = new ArrayList<>(vector.size());
        for (JsonNode value : vector) {
            if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                return List.of();
            }
            values.add(value.floatValue());
        }
        return values;
    }

    private boolean isCurrent(BenefitDocument document, JsonNode existing) {
        ObjectNode expected = objectMapper.valueToTree(document);
        expected.remove(List.of("embedding", "indexedAt", "deletedAt"));
        var fields = expected.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode actual = existing.get(field.getKey());
            if (field.getValue().isNull() ? actual != null && !actual.isNull() : !field.getValue().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static String textOrBlank(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean booleanOrDefault(JsonNode node, String fieldName, boolean defaultValue) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    private static Integer integerOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> textList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
            return values;
        }
        return value.asText().isBlank() ? List.of() : List.of(value.asText());
    }

    public record SyncResult(int scannedBenefits,
                             int candidateDocuments,
                             int upsertedDocuments,
                             int skippedDocuments,
                             int tombstonedDocuments,
                             int deletedDocuments,
                             int failedDocuments) {
    }

    private record ExistingDocumentSnapshot(String documentId, JsonNode node, boolean active) {
    }

    private record ReconciliationResult(int tombstonedDocuments, int deletedDocuments, int failedDocuments) {
    }
}
