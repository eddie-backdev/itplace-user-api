package com.itplace.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ItplaceUserApiApplication;
import com.itplace.userapi.ai.chat.BenefitChatService;
import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.rag.postgres.PostgresBenefitRagSyncService;
import com.itplace.userapi.ai.rag.postgres.PostgresBenefitSearchRepository;
import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local-only, no-paid-call PostgreSQL search preparation and verification. */
public final class PostgresSearchPreparation {
    private static final List<String> QUERIES = List.of("편의점", "영화 할인", "카페", "학원", "CU", "씨유", "지에스25", "FLO");
    private PostgresSearchPreparation() {}

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            System.err.println("Preparation failed: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2 || !Set.of("audit", "stage", "verify", "embed").contains(args[0]))
            throw new IllegalArgumentException("usage: audit|stage|verify|embed OUTPUT_DIR [EXPECTED_HASH]");
        String mode = args[0]; Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Set.of("stage", "embed").contains(mode) && (args.length != 3 || !args[2].matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException(mode + " requires a reviewed SHA-256 fingerprint");
        if (!Set.of("stage", "embed").contains(mode) && args.length != 2) throw new IllegalArgumentException("unexpected arguments");
        Files.createDirectories(output);
        SpringApplication app = new SpringApplication(ItplaceUserApiApplication.class);
        if (!mode.equals("embed")) app.addPrimarySources(Set.of(NoPaidCalls.class));
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(beanFactory -> {
            String scheduledProcessor = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
            // Ordinary BeanFactoryPostProcessors run after registry post-processors, including configuration parsing and @EnableScheduling imports.
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)
                    || !registry.containsBeanDefinition(scheduledProcessor))
                throw new IllegalStateException("Scheduled method processor was not registered");
            registry.removeBeanDefinition(scheduledProcessor);
        }));
        app.setRegisterShutdownHook(true);
        try (ConfigurableApplicationContext ctx = app.run(
                "--spring.profiles.active=local,postgres", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.data.redis.database=15", "--spring.flyway.enabled=false", "--app.ai.enabled=false",
                "--app.ai.benefits.seed.enabled=false", "--app.ai.benefits.sync.enabled=false",
                "--app.ai.stores.seed.enabled=false", "--app.ai.questions.seed.enabled=false",
                "--app.map.cluster-snapshot.enabled=false", "--app.map.cluster-summary.refresh.enabled=false",
                "--spring.jmx.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                "--logging.level.com.itplace.userapi.ai.rag.postgres=INFO")) {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            PostgresBenefitRagSyncService sync = ctx.getBean(PostgresBenefitRagSyncService.class);
            Map<String,Object> summary = new LinkedHashMap<>(); summary.put("mode", mode);
            if (mode.equals("audit")) {
                var audit = sync.dryRun();
                write(mapper, output.resolve("source-audit.json"), audit);
                write(mapper, output.resolve("source-snapshot.json"), Map.of(
                        "scannedBenefits", audit.scannedBenefits(), "pendingDocuments", audit.documents()));
                summary.put("scannedBenefits", audit.scannedBenefits()); summary.put("documents", audit.documentCount());
                summary.put("activeDocuments", audit.documents().stream().filter(d -> !Boolean.FALSE.equals(d.active())).count());
                summary.put("inactiveDocuments", audit.documents().stream().filter(d -> Boolean.FALSE.equals(d.active())).count());
                summary.put("snapshotHash", audit.snapshotHash()); summary.put("errors", audit.errors().size()); summary.put("warnings", audit.warnings().size());
                if (!audit.errors().isEmpty()) throw new IllegalStateException("source audit has errors");
            } else if (Set.of("stage", "embed").contains(mode)) {
                var audit = sync.dryRun();
                write(mapper, output.resolve("stage-source-audit.json"), audit);
                write(mapper, output.resolve("stage-source-snapshot.json"), Map.of(
                        "scannedBenefits", audit.scannedBenefits(), "pendingDocuments", audit.documents()));
                summary.put("snapshotHash", audit.snapshotHash());
                summary.put("activeDocuments", audit.documents().stream().filter(d -> !Boolean.FALSE.equals(d.active())).count());
                summary.put("inactiveDocuments", audit.documents().stream().filter(d -> Boolean.FALSE.equals(d.active())).count());
                if (!audit.errors().isEmpty()) throw new IllegalStateException("source audit has errors");
                var result = mode.equals("stage") ? sync.stageValidatedSnapshot(args[2]) : sync.embedLocalValidatedSnapshot(args[2]);
                write(mapper, output.resolve(mode + "-result.json"), result);
                summary.put("scannedBenefits", result.scannedBenefits()); summary.put("documents", result.documents());
                summary.put("written", result.written()); summary.put("reusedEmbeddings", result.reusedEmbeddings());
                summary.put("stale", result.stale()); summary.put("failedPolicies", result.failures().size());
                if (mode.equals("embed")) {
                    Map<String,Object> report = embeddingReport(ctx, audit);
                    write(mapper, output.resolve("embedding-verification.json"), report);
                    summary.put("embeddingVerification", report);
                    if (!Boolean.TRUE.equals(report.get("complete"))) throw new IllegalStateException("embedding verification incomplete");
                } else if (result.stale() != 0 || !result.failures().isEmpty() || result.written() != result.documents()) {
                    throw new IllegalStateException("stage incomplete");
                }
            } else {
                verify(ctx, mapper, sync, output, summary);
            }
            if (!mode.equals("embed")) {
                int calls = ctx.getBean(NoPaidCalls.Counter.class).calls.get();
                summary.put("embeddingCalls", calls);
                if (calls != 0) throw new IllegalStateException("embedding call guard triggered");
            }
            System.out.println(mapper.writeValueAsString(summary));
        }
    }

    private static Map<String,Object> embeddingReport(ConfigurableApplicationContext ctx,
            PostgresBenefitRagSyncService.Audit audit) {
        JdbcTemplate jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
        var settings = ctx.getBean(com.itplace.userapi.ai.rag.service.EmbeddingSettings.class);
        Map<String, PostgresBenefitRagSyncService.DocumentAudit> expected = new HashMap<>();
        audit.documents().stream().filter(d -> !Boolean.FALSE.equals(d.active()))
                .forEach(d -> expected.put(d.documentId(), d));
        Map<String,Object[]> actual = new HashMap<>();
        jdbc.query("SELECT document_id, content_hash, embedding_model, embedding_dimensions, embedding_version, embedding IS NOT NULL, active " +
                "FROM benefit_search_document", (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.put(rs.getString(1), new Object[]{rs.getString(2).trim(), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)}));
        long activeStored = expected.keySet().stream().filter(id -> actual.containsKey(id) && Boolean.TRUE.equals(actual.get(id)[5])).count();
        long activeVectors = expected.keySet().stream().filter(id -> actual.containsKey(id)
                && Boolean.TRUE.equals(actual.get(id)[5]) && Boolean.TRUE.equals(actual.get(id)[4])).count();
        long contentHashMatches = expected.entrySet().stream().filter(entry -> {
            Object[] row = actual.get(entry.getKey());
            return row != null && entry.getValue().document().getContentHash().equals(row[0]);
        }).count();
        long vectors = expected.entrySet().stream().filter(entry -> {
            Object[] row = actual.get(entry.getKey());
            var doc = entry.getValue().document();
            return row != null && Boolean.TRUE.equals(row[4]) && Boolean.TRUE.equals(row[5])
                    && doc.getContentHash().equals(row[0]) && settings.modelIdentity().equals(row[1])
                    && settings.dimensions() == (Integer) row[2] && java.util.Objects.equals(doc.getEmbeddingVersion(), row[3]);
        }).count();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("activeSourceDocuments", expected.size());
        report.put("activeStoredDocuments", activeStored);
        report.put("activeDocumentsWithVectors", activeVectors);
        report.put("contentHashMatches", contentHashMatches);
        report.put("activeVectorDocuments", vectors);
        report.put("coverage", expected.isEmpty() ? 0d : (double) vectors / expected.size());
        report.put("modelIdentity", settings.modelIdentity());
        report.put("dimensions", settings.dimensions());
        report.put("snapshotHash", audit.snapshotHash());
        report.put("complete", !expected.isEmpty() && vectors == expected.size());
        return report;
    }

    private static void verify(ConfigurableApplicationContext ctx, ObjectMapper mapper, PostgresBenefitRagSyncService sync,
                               Path output, Map<String,Object> summary) throws Exception {
        var audit = sync.dryRun();
        write(mapper, output.resolve("current-source-audit.json"), audit);
        JdbcTemplate jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
        var state = jdbc.queryForMap("SELECT count(*) AS documents, count(*) FILTER (WHERE active) AS active_documents, " +
                "count(*) FILTER (WHERE NOT active) AS inactive_documents, count(*) FILTER (WHERE embedding IS NOT NULL) AS vectors, " +
                "count(DISTINCT benefitcarrierpolicyid) AS policies " +
                "FROM benefit_search_document");
        var groups = jdbc.queryForList("SELECT grade, is_all_grade, count(*) AS documents, " +
                "count(DISTINCT offer_key) AS offers, count(*) FILTER (WHERE active) AS active_documents " +
                "FROM benefit_search_document GROUP BY grade, is_all_grade ORDER BY grade, is_all_grade");
        Map<String,List<String>> expected = new TreeMap<>(), actual = new TreeMap<>();
        audit.documents().forEach(d -> {
            String columns = identity(d.documentId(), d.policyId(), d.tierBenefitId(), d.document().getOfferKey(), d.carrier(),
                    d.grade(), Boolean.TRUE.equals(d.allGrade()), !Boolean.FALSE.equals(d.active()), d.benefitId(), d.contentHash());
            String metadata = identity(d.documentId(), d.policyId(), d.tierBenefitId(), d.document().getOfferKey(), d.carrier(),
                    d.grade(), d.allGrade(), d.active(), d.benefitId(), d.contentHash());
            expected.computeIfAbsent(d.policyId(), ignored -> new ArrayList<>()).add(columns + "|meta|" + metadata);
        });
        jdbc.query("SELECT document_id, benefitcarrierpolicyid, carriertierbenefitid, offer_key, carrier, grade, " +
                "is_all_grade, active, benefitid, content_hash, metadata->>'documentId', metadata->>'policyId', " +
                "metadata->>'tierBenefitId', metadata->>'offerKey', metadata->>'carrier', metadata->>'grade', " +
                "metadata->>'isAllGrade', metadata->>'active', metadata->>'benefitId', metadata->>'contentHash' " +
                "FROM benefit_search_document", rs -> {
            String policy = rs.getString(2);
            String columns = identity(rs.getString(1), policy, rs.getObject(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getObject(7), rs.getBoolean(8), rs.getObject(9), rs.getString(10).trim());
            String metadata = identity(rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                    rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20));
            actual.computeIfAbsent(policy, ignored -> new ArrayList<>()).add(columns + "|meta|" + metadata);
        });
        expected.values().forEach(Collections::sort); actual.values().forEach(Collections::sort);
        Set<String> policies = new TreeSet<>(expected.keySet()); policies.addAll(actual.keySet());
        List<Map<String,Object>> policyHashes = policies.stream().map(policy -> Map.<String,Object>of(
                "policyId", policy, "sourceDocumentCount", expected.getOrDefault(policy, List.of()).size(),
                "storedDocumentCount", actual.getOrDefault(policy, List.of()).size(),
                "current", expected.getOrDefault(policy, List.of()).equals(actual.getOrDefault(policy, List.of())))).toList();
        var search = ctx.getBean(PostgresBenefitSearchRepository.class);
        List<Map<String,Object>> queries = new ArrayList<>();
        for (String q : QUERIES) queries.add(Map.of("query", q, "hits", search.searchLexical(q, null, null, BenefitSearchCondition.none(), 10)));
        var chat = ctx.getBean(BenefitChatService.class); List<Map<String,Object>> chats = new ArrayList<>();
        boolean chatUnavailable = false;
        for (String q : QUERIES) {
            var response = chat.chat(new BenefitChatRequest(q, List.of(), List.of(), null, null, null, null), null);
            chats.add(Map.of("query", q, "response", response));
            chatUnavailable |= "UNAVAILABLE".equals(response.status());
        }
        write(mapper, output.resolve("verification.json"), Map.of("sourceSnapshotHash", audit.snapshotHash(),
                "sourceErrors", audit.errors(), "storage", state, "coverageByGrade", groups,
                "policyHashes", policyHashes, "search", queries, "chat", chats));
        summary.put("storage", state); summary.put("gradeGroups", groups.size()); summary.put("queries", queries.size());
        summary.put("chatQueries", chats.size()); summary.put("vectors", state.get("vectors"));
        summary.put("sourceSnapshotHash", audit.snapshotHash());
        summary.put("policyHashMismatches", policyHashes.stream().filter(p -> !Boolean.TRUE.equals(p.get("current"))).count());
        summary.put("chatUnavailable", chatUnavailable);
        if (((Number) state.get("documents")).intValue() == 0) throw new IllegalStateException("search storage is empty");
        if (((Number) state.get("vectors")).intValue() != 0) throw new IllegalStateException("preparation baseline contains vectors");
        if (chatUnavailable) throw new IllegalStateException("chat retrieval unavailable for a verification query");
        if (!audit.errors().isEmpty() || policyHashes.stream().anyMatch(p -> !Boolean.TRUE.equals(p.get("current"))))
            throw new IllegalStateException("source and stored policy snapshots differ");
    }

    private static String identity(Object... fields) {
        return java.util.Arrays.stream(fields).map(value -> Objects.toString(value, "")).collect(java.util.stream.Collectors.joining("|"));
    }

    private static void write(ObjectMapper mapper, Path path, Object value) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    @Configuration(proxyBeanMethods = false)
    static class NoPaidCalls {
        static final class Counter { final AtomicInteger calls = new AtomicInteger(); }
        @Bean Counter noPaidCallCounter() { return new Counter(); }
        @Bean @Primary EmbeddingService preparationEmbeddingGuard(Counter counter) {
            return text -> { counter.calls.incrementAndGet(); throw new IllegalStateException("paid embedding disabled"); };
        }
    }
}
