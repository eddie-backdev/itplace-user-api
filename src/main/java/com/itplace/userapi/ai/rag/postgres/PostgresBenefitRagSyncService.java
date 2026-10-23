package com.itplace.userapi.ai.rag.postgres;

import com.itplace.userapi.ai.rag.index.BenefitRagDocumentBuilder;
import com.itplace.userapi.ai.rag.index.BenefitRagSourceQueryService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.ai.rag.service.EmbeddingSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PostgresBenefitRagSyncService {
    private static final Pattern FLOAT = Pattern.compile("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Logger log = LoggerFactory.getLogger(PostgresBenefitRagSyncService.class);
    private final BenefitRagSourceQueryService source;
    private final PostgresBenefitDocumentStore store;
    private final EmbeddingService embeddings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EmbeddingSettings embeddingSettings;
    @Value("${app.search.provider:elasticsearch}")
    private String searchProvider = "elasticsearch";
    @Value("${app.ai.benefits.sync.enabled:false}")
    private boolean syncEnabled;

    public PostgresBenefitRagSyncService(BenefitRagSourceQueryService source, PostgresBenefitDocumentStore store,
                                        EmbeddingService embeddings,
                                        EmbeddingSettings embeddingSettings) {
        this.source = source; this.store = store; this.embeddings = embeddings; this.embeddingSettings = embeddingSettings;
    }

    /** No model calls or writes. The full row-level document audit is returned to the caller. */
    public Audit dryRun() {
        return audit(source.loadSourceSnapshotFromPrimary());
    }

    /** Paid embeddings require an explicit fingerprint from a reviewed dry-run. */
    public SyncResult syncValidatedSnapshot(String expectedSnapshotHash) {
        BenefitRagSourceQueryService.SourceSnapshot snapshot = source.loadSourceSnapshotFromPrimary();
        Audit audit = audit(snapshot);
        if (!audit.errors().isEmpty()) throw new IllegalStateException("RAG source audit failed: " + audit.errors());
        requireExpectedSnapshot(expectedSnapshotHash, audit);
        return applySnapshot(snapshot, true);
    }

    /** Explicitly approved local backfill path. It cannot invoke a paid or remote provider. */
    public SyncResult embedLocalValidatedSnapshot(String expectedSnapshotHash) {
        requireLocalProvider();
        return syncValidatedSnapshot(expectedSnapshotHash);
    }

    @Scheduled(fixedDelayString = "${app.ai.benefits.sync.fixed-delay-ms:3600000}",
            initialDelayString = "${app.ai.benefits.sync.initial-delay-ms:300000}")
    public void scheduledSync() {
        if (!"postgres".equalsIgnoreCase(searchProvider) || !syncEnabled) return;
        try {
            Audit audit = dryRun();
            if (!audit.errors().isEmpty()) {
                log.error("PostgreSQL RAG sync skipped: source audit errors={}", audit.errors().size());
                return;
            }
            SyncResult result = embedLocalValidatedSnapshot(audit.snapshotHash());
            log.info("PostgreSQL RAG sync completed: scanned={}, documents={}, written={}, reused={}, stale={}, failed={}",
                    result.scannedBenefits(), result.documents(), result.written(), result.reusedEmbeddings(),
                    result.stale(), result.failures().size());
        } catch (Exception e) {
            log.error("PostgreSQL RAG sync failed: errorType={}", e.getClass().getSimpleName());
        }
    }

    /** Stages validated lexical documents while reusing only matching vectors already in PostgreSQL. */
    public SyncResult stageValidatedSnapshot(String expectedSnapshotHash) {
        BenefitRagSourceQueryService.SourceSnapshot snapshot = source.loadSourceSnapshotFromPrimary();
        Audit audit = audit(snapshot);
        if (!audit.errors().isEmpty()) throw new IllegalStateException("RAG source audit failed: " + audit.errors());
        requireExpectedSnapshot(expectedSnapshotHash, audit);
        return applySnapshot(snapshot, false);
    }

    private SyncResult applySnapshot(BenefitRagSourceQueryService.SourceSnapshot snapshot, boolean allowEmbedding) {
        Map<Long,List<BenefitRagDocumentBuilder.PendingBenefitDocument>> byPolicy = new LinkedHashMap<>();
        for (var pending : snapshot.pendingDocuments()) {
            byPolicy.computeIfAbsent(Long.valueOf(pending.document().getPolicyId()), ignored -> new ArrayList<>()).add(pending);
        }
        int written=0, reused=0, stale=0;
        List<PolicyFailure> failures = new ArrayList<>();
        Map<EmbeddingKey,List<Float>> runVectorCache = new HashMap<>();
        for (var entry : byPolicy.entrySet()) {
            log.info("PostgreSQL RAG policy started: policyId={}, documents={}", entry.getKey(), entry.getValue().size());
            try {
                if (!store.isCurrentSnapshot(entry.getKey(), entry.getValue())) {
                    stale += entry.getValue().size();
                    continue;
                }
                List<PostgresBenefitDocumentStore.StoredDocument> docs = new ArrayList<>();
                Map<String,List<Float>> vectors = new LinkedHashMap<>();
                Map<String,EmbeddingKey> documentKeys = new LinkedHashMap<>();
                Map<EmbeddingKey,String> toEmbed = new LinkedHashMap<>();
                for (var pending : entry.getValue()) {
                    var d = pending.document();
                    if (!Boolean.TRUE.equals(d.getActive())) continue;
                    EmbeddingKey key = embeddingKey(d);
                    documentKeys.put(d.getDocumentId(), key);
                    List<Float> cached = runVectorCache.get(key);
                    if (cached != null) {
                        vectors.put(d.getDocumentId(), cached);
                        continue;
                    }
                    PostgresBenefitDocumentStore.ExistingEmbedding old = store.findEmbedding(d.getDocumentId());
                    if (reusable(old, d)) {
                        List<Float> vector = parseVector(old.vector()); validateVector(vector);
                        runVectorCache.put(key, vector);
                        vectors.put(d.getDocumentId(), vector);
                        toEmbed.remove(key);
                    } else if (allowEmbedding) {
                        toEmbed.putIfAbsent(key, pending.searchableText());
                    }
                }
                int embeddedUniqueKeys = 0;
                if (!toEmbed.isEmpty()) {
                    List<List<Float>> batch = embeddings.embedBatch(List.copyOf(toEmbed.values()));
                    if (batch == null || batch.size() != toEmbed.size()) throw new IllegalStateException("Embedding batch result count mismatch");
                    List<List<Float>> validatedBatch = new ArrayList<>(batch.size());
                    for (List<Float> vector : batch) {
                        validateVector(vector);
                        validatedBatch.add(List.copyOf(vector));
                    }
                    int index = 0;
                    for (EmbeddingKey key : toEmbed.keySet()) runVectorCache.put(key, validatedBatch.get(index++));
                    embeddedUniqueKeys = toEmbed.size();
                }
                for (var pending : entry.getValue()) {
                    var d=pending.document();
                    EmbeddingKey key = documentKeys.get(d.getDocumentId());
                    if (key != null && !vectors.containsKey(d.getDocumentId())) vectors.put(d.getDocumentId(), runVectorCache.get(key));
                    docs.add(new PostgresBenefitDocumentStore.StoredDocument(d,pending.searchableText(),vectors.get(d.getDocumentId())));
                }
                int groupReused = Math.toIntExact(docs.stream().filter(d -> d.embedding()!=null).count()) - embeddedUniqueKeys;
                store.replacePolicy(entry.getKey(), docs, embeddingSettings.modelIdentity());
                written += docs.size(); reused += groupReused;
            } catch (PostgresBenefitDocumentStore.StaleSnapshotException e) {
                stale += entry.getValue().size();
                log.warn("PostgreSQL RAG policy skipped as stale: policyId={}", entry.getKey());
            } catch (Exception e) {
                failures.add(new PolicyFailure(entry.getKey(), e.getClass().getSimpleName()));
                log.error("PostgreSQL RAG policy failed: policyId={}, errorType={}", entry.getKey(), e.getClass().getSimpleName());
            }
            log.info("PostgreSQL RAG policy completed: policyId={}, written={}, reused={}, stale={}, failed={}",
                    entry.getKey(), written, reused, stale, failures.size());
        }
        return new SyncResult(snapshot.scannedBenefits(), snapshot.pendingDocuments().size(), written, reused, stale, failures);
    }

    private Audit audit(BenefitRagSourceQueryService.SourceSnapshot snapshot) {
        List<String> errors = validationErrors(snapshot.scannedBenefits(), snapshot.pendingDocuments());
        List<String> warnings = new ArrayList<>();
        List<DocumentAudit> docs = snapshot.pendingDocuments().stream().map(p -> {
            String issue = issue(p, Boolean.TRUE.equals(p.document().getActive()), errors, warnings);
            return audit(p, issue);
        }).toList();
        return new Audit(snapshot.scannedBenefits(), docs.size(), snapshotHash(snapshot.pendingDocuments()), errors, warnings, docs);
    }

    private void requireExpectedSnapshot(String expected, Audit actual) {
        if (expected == null || expected.isBlank() || !expected.equals(actual.snapshotHash()))
            throw new IllegalStateException("RAG source snapshot changed or has no reviewed fingerprint");
    }

    private String snapshotHash(List<BenefitRagDocumentBuilder.PendingBenefitDocument> documents) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (var pending : documents.stream().sorted(java.util.Comparator.comparing(
                    p -> p.document().getDocumentId(), java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).toList()) {
                var document=pending.document();
                JsonNode canonical=canonicalize(mapper.valueToTree(document));
                byte[] metadata=mapper.writeValueAsBytes(canonical);
                digest.update(String.valueOf(document.getDocumentId()).getBytes(StandardCharsets.UTF_8)); digest.update((byte)'|');
                digest.update(String.valueOf(document.getContentHash()).getBytes(StandardCharsets.UTF_8)); digest.update((byte)'|');
                digest.update(metadata); digest.update((byte)'\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Unable to fingerprint RAG source snapshot", e); }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted=mapper.createObjectNode();
            java.util.TreeMap<String,JsonNode> fields=new java.util.TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(),e.getValue()));
            fields.forEach((key,value)->sorted.set(key,canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array=mapper.createArrayNode(); node.forEach(value->array.add(canonicalize(value))); return array;
        }
        return node;
    }

    private boolean reusable(PostgresBenefitDocumentStore.ExistingEmbedding old, com.itplace.userapi.ai.rag.document.BenefitDocument d) {
        return old != null && old.vector()!=null && !old.vector().isBlank()
                && embeddingSettings.modelIdentity().equals(old.model()) && old.dimensions()==embeddingSettings.dimensions()
                && d.getEmbeddingVersion()!=null && d.getEmbeddingVersion().equals(old.version())
                && d.getContentHash()!=null && d.getContentHash().equals(old.contentHash());
    }

    private EmbeddingKey embeddingKey(com.itplace.userapi.ai.rag.document.BenefitDocument document) {
        return new EmbeddingKey(embeddingSettings.modelIdentity(), document.getEmbeddingVersion(), document.getContentHash());
    }

    private List<String> validationErrors(int scanned, List<BenefitRagDocumentBuilder.PendingBenefitDocument> docs) {
        List<String> errors = new ArrayList<>();
        if (scanned == 0) errors.add("source snapshot contains no benefits");
        if (docs.isEmpty()) errors.add("source snapshot contains no buildable documents");
        Set<String> keys = new HashSet<>();
        for (var p : docs) {
            var d=p.document();
            if (d.getDocumentId()==null || !keys.add(d.getDocumentId())) errors.add("missing or duplicate document identity: " + d.getDocumentId());
            if (d.getBenefitId()==null || d.getPolicyId()==null || d.getCarrier()==null) errors.add("missing required source key: " + d.getDocumentId());
            if (p.searchableText()==null || p.searchableText().isBlank() || d.getContentHash()==null || d.getContentHash().length()!=64)
                errors.add("missing searchable text or content hash: " + d.getDocumentId());
        }
        return errors;
    }

    private String issue(BenefitRagDocumentBuilder.PendingBenefitDocument p, boolean active,
                         List<String> errors, List<String> warnings) {
        if (!active) return "inactive: stored without embedding";
        var d=p.document(); List<String> issues=new ArrayList<>();
        if (blank(d.getSourceUrl())) issues.add("missing source URL");
        if (blank(d.getSourceCapturedAt())) issues.add("missing source capture time");
        if (d.getSourceEvidenceId() == null) warnings.add(d.getDocumentId()+": no database evidence row; external manifest must be retained");
        if (blank(d.getSourceCategory())) warnings.add(d.getDocumentId()+": source category is unknown");
        if (blank(d.getDescription()) && blank(d.getManual())) issues.add("missing description and manual facts");
        if (d.getTags()!=null && d.getTags().stream().anyMatch(tag -> !SUPPORTED_TAGS.contains(tag))) issues.add("unsupported metadata tag");
        if (!issues.isEmpty()) errors.add(d.getDocumentId()+": "+String.join(", ",issues));
        if ("OTHER".equals(d.getBusinessType())) warnings.add(d.getDocumentId()+": business type remains OTHER");
        return String.join(", ",issues);
    }

    private DocumentAudit audit(BenefitRagDocumentBuilder.PendingBenefitDocument p, String issue) {
        var d=p.document();
        return new DocumentAudit(d.getDocumentId(), d.getBenefitId(), d.getPolicyId(), d.getTierBenefitId(), d.getSourceEvidenceId(),
                d.getCarrier(), d.getGrade(), d.getIsAllGrade(), d.getActive(), d.getEmbeddingVersion(),
                d.getContentHash(), p.searchableText(), d, issue);
    }

    private static List<Float> parseVector(String raw) {
        Matcher m=FLOAT.matcher(raw); List<Float> values=new ArrayList<>();
        while(m.find()) values.add(Float.parseFloat(m.group()));
        return values;
    }
    private void validateVector(List<Float> v) {
        if (v==null || v.size()!=embeddingSettings.dimensions() || v.stream().anyMatch(x -> x==null || !Float.isFinite(x))
                || v.stream().mapToDouble(x -> (double)x*x).sum()==0) throw new IllegalStateException("Invalid embedding vector");
    }
    private void requireLocalProvider() {
        if (!Set.of("ollama", "local").contains(embeddingSettings.provider().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalStateException("Embed mode requires a local embedding provider");
        try {
            java.net.URI uri = java.net.URI.create(embeddingSettings.baseUrl());
            String host = uri.getHost();
            if (host != null) host = host.replace("[", "").replace("]", "");
            boolean loopback = host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
            if (host == null || (!loopback && !embeddingSettings.isTrustedHost(host)))
                throw new IllegalStateException("Embed mode requires a loopback or explicitly trusted embedding host");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Embed mode requires a valid loopback or trusted embedding base URL");
        }
    }
    private static boolean blank(String value) { return value==null||value.isBlank(); }
    private static final Set<String> SUPPORTED_TAGS = Set.of("KIDS_PLAY","STUDY_SPACE","EDUCATION","BEVERAGE_CAFE",
            "CONVENIENCE_STORE","FOOD_RESTAURANT","ATTRACTION","CULTURE","SHOPPING","DESSERT","MOVIE_THEATER",
            "WATER_PARK","COUNSELING","AUTO_SERVICE","HOTEL");

    public record DocumentAudit(String documentId, String benefitId, String policyId, String tierBenefitId, Long sourceEvidenceId,
                                String carrier, String grade, Boolean allGrade, Boolean active, String embeddingVersion,
                                String contentHash, String searchableText, com.itplace.userapi.ai.rag.document.BenefitDocument document,
                                String issue) {}
    public record Audit(int scannedBenefits, int documentCount, String snapshotHash, List<String> errors, List<String> warnings, List<DocumentAudit> documents) {}
    public record PolicyFailure(long policyId, String errorType) {}
    public record SyncResult(int scannedBenefits, int documents, int written, int reusedEmbeddings, int stale, List<PolicyFailure> failures) {}
    private record EmbeddingKey(String model, String version, String contentHash) {}
}
