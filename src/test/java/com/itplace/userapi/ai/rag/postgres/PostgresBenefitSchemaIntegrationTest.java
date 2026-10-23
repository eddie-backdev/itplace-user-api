package com.itplace.userapi.ai.rag.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.index.BenefitRagDocumentBuilder;
import com.itplace.userapi.ai.rag.index.BenefitRagSourceQueryService;
import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBenefitSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg18-trixie");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private BenefitRagSourceQueryService source;
    private PostgresBenefitDocumentStore store;
    private Map<Long, List<BenefitRagDocumentBuilder.PendingBenefitDocument>> sourceSnapshots;

    @BeforeEach
    void applyRealMigrationToMinimalSourceSchema() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE partner(partnerId bigint PRIMARY KEY, category text)");
        jdbc.execute("CREATE TABLE benefit(benefitId bigint PRIMARY KEY, partnerId bigint, active boolean, maincategory text)");
        jdbc.execute("CREATE TABLE benefitCarrierPolicy(benefitCarrierPolicyId bigint PRIMARY KEY, benefitId bigint NOT NULL, carrier text, usageType text, active boolean)");
        jdbc.execute("CREATE TABLE carrierTierBenefit(carrierTierBenefitId bigint PRIMARY KEY, benefitCarrierPolicyId bigint NOT NULL, grade text NOT NULL, isAll boolean, context text, CONSTRAINT carrierTierBenefit_grade_check CHECK (grade IN ('BASIC','VIP','VVIP','VIP콕','SKT_SILVER','SKT_GOLD','SKT_VIP','KT_GENERAL','KT_WHITE','KT_SILVER','KT_GOLD','KT_VIP','KT_VVIP')))");
        jdbc.execute("CREATE TABLE tierBenefit(grade text NOT NULL CONSTRAINT tierBenefit_grade_check CHECK (grade IN ('BASIC','VIP','VVIP','VIP콕','SKT_SILVER','SKT_GOLD','SKT_VIP','KT_GENERAL','KT_WHITE','KT_SILVER','KT_GOLD','KT_VIP','KT_VVIP')))");
        jdbc.execute("CREATE TABLE users(carrier text, membershipgradecode text CONSTRAINT users_membershipgradecode_check CHECK (membershipgradecode IS NULL OR membershipgradecode IN ('BASIC','VIP','VVIP','VIP콕','SKT_SILVER','SKT_GOLD','SKT_VIP','KT_GENERAL','KT_WHITE','KT_SILVER','KT_GOLD','KT_VIP','KT_VVIP')), \"membershipGradeCode\" text CONSTRAINT \"users_membershipGradeCode_check\" CHECK (\"membershipGradeCode\" IS NULL OR \"membershipGradeCode\" IN ('BASIC','VIP','VVIP','VIP콕','SKT_SILVER','SKT_GOLD','SKT_VIP','KT_GENERAL','KT_WHITE','SKT_LITE','KT_GOLD','KT_VIP','KT_VVIP')))");
        jdbc.update("INSERT INTO partner VALUES (9)");
        jdbc.update("INSERT INTO benefit VALUES (1, 9, true)");
        jdbc.update("INSERT INTO benefitCarrierPolicy VALUES (10, 1, 'SKT', 'online', true), (20, 1, 'SKT', 'online', true)");
        jdbc.update("INSERT INTO carrierTierBenefit(carrierTierBenefitId,benefitCarrierPolicyId,grade,isAll,context) VALUES (101, 10, 'SKT_VIP', false,'vip'), (102, 10, 'SKT_GOLD', false,'gold'), (103, 10, 'SKT_VIP', false,'vip')");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("20261020.0")).load().migrate();
        Flyway postgresFlyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/migration-postgresql")
                .outOfOrder(true).target(MigrationVersion.fromVersion("20261022.2")).load();
        postgresFlyway.migrate();
        jdbc.update("INSERT INTO benefit_search_document(document_id,benefitid,benefitcarrierpolicyid,carriertierbenefitid,carrier,grade,is_all_grade,usage_type,active,searchable_text,metadata,embedding,embedding_model,embedding_dimensions,embedding_version,content_hash) " +
                        "VALUES ('benefit:1:policy:10:tier:101',1,10,101,'SKT','SKT_VIP',false,'online',true,'legacy vector', " +
                        "'{\"documentId\":\"benefit:1:policy:10:tier:101\",\"benefitId\":\"1\",\"policyId\":\"10\",\"tierBenefitId\":\"101\",\"grade\":\"SKT_VIP\",\"contentHash\":\"%s\"}'::jsonb, ?::vector, 'text-embedding-3-small',1536,'benefit-carrier-grade-rag-v3', '%s')"
                        .formatted("a".repeat(64), "a".repeat(64)), vectorLiteral(1536));
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/migration-postgresql")
                .outOfOrder(true).load().migrate();
        assertThat(postgresFlyway.info().applied())
                .as("applied migration states: %s", java.util.Arrays.stream(postgresFlyway.info().applied())
                        .map(migration -> migration.getScript() + "=" + migration.getState()).toList())
                .anyMatch(migration -> "V20261020_0001__create_benefit_search_document.sql".equals(migration.getScript())
                        && migration.getState() == MigrationState.OUT_OF_ORDER)
                .anyMatch(migration -> "V20261022_0002__support_offer_grade_search_documents.sql".equals(migration.getScript())
                        && migration.getState() == MigrationState.SUCCESS);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        source = mock(BenefitRagSourceQueryService.class);
        store = new PostgresBenefitDocumentStore(jdbc, new ObjectMapper(), source, settings());
        sourceSnapshots = new java.util.HashMap<>();
        when(source.loadPolicySnapshotFromPrimary(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> sourceSnapshots.getOrDefault(invocation.getArgument(0), List.of()));
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM benefit_search_document WHERE document_id='benefit:1:policy:10:tier:101'", Integer.class))
                .isEqualTo(1536);
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM benefit_search_document WHERE document_id='benefit:1:policy:10:tier:101'", String.class))
                .startsWith("[1,");
    }

    @Test
    void realMigrationStoresMetadataVectorsAndNullTierWithoutDeletingSiblingPolicyAndRollsBackBadReplacement() {
        var vip = pending(document("benefit:1:policy:10:tier:101", 1, 10, 101L, "SKT_VIP", false, "vip"), "VIP 영화 할인");
        var gold = pending(document("benefit:1:policy:10:tier:102", 1, 10, 102L, "SKT_GOLD", false, "gold"), "GOLD 영화 할인");
        var noTier = pending(document("benefit:1:policy:20:tier:policy", 1, 20, null, null, false, "policy-only"), "영화 혜택");
        sourceSnapshots.put(10L, List.of(vip, gold));
        sourceSnapshots.put(20L, List.of(noTier));

        transactions.executeWithoutResult(status -> store.replacePolicy(10, List.of(stored(vip, vector(1f)), stored(gold, null)), "text-embedding-3-small"));
        transactions.executeWithoutResult(status -> store.replacePolicy(20, List.of(stored(noTier, null)), "text-embedding-3-small"));

        assertThat(store.findEmbedding(vip.document().getDocumentId()).vector()).startsWith("[");
        assertThat(store.findEmbedding(gold.document().getDocumentId()).vector()).isNull();
        assertThat(jdbc.queryForObject("SELECT metadata->>'sourceEvidenceId' FROM benefit_search_document WHERE document_id=?", String.class,
                vip.document().getDocumentId())).isEqualTo("77");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benefit_search_document WHERE benefitcarrierpolicyid=20", Integer.class)).isEqualTo(1);

        var repository = new PostgresBenefitSearchRepository(jdbc, settings());
        assertThat(repository.searchLexical("영화", Carrier.SKT, Grade.SKT_VIP, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::tierBenefitId).containsExactly(101L);
        assertThat(repository.searchLexical("영화", Carrier.SKT, Grade.SKT_GOLD, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::tierBenefitId).containsExactly(102L);
        jdbc.update("INSERT INTO carrierTierBenefit(carrierTierBenefitId,benefitCarrierPolicyId,grade,isAll,context) VALUES (104, 10, 'SKT_LITE', false,'lite')");
        jdbc.update("INSERT INTO tierBenefit(grade) VALUES ('SKT_LITE')");
        jdbc.update("INSERT INTO users(carrier, membershipgradecode, \"membershipGradeCode\") VALUES ('SKT', 'SKT_LITE', 'SKT_LITE')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carrierTierBenefit WHERE grade='SKT_LITE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tierBenefit WHERE grade='SKT_LITE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE membershipGradeCode='SKT_LITE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE \"membershipGradeCode\"='SKT_LITE'", Integer.class)).isEqualTo(1);

        var nextVip = pending(document("benefit:1:policy:10:tier:101", 1, 10, 101L, "SKT_VIP", false, "updated"), "VIP 업데이트");
        var newTier = pending(document("benefit:1:policy:10:tier:103", 1, 10, 103L, "SKT_VIP", false, "new"), "VIP 신규");
        sourceSnapshots.put(10L, List.of(nextVip, newTier));
        List<PostgresBenefitDocumentStore.StoredDocument> badReplacement = List.of(
                stored(nextVip, vector(0.5f)),
                new PostgresBenefitDocumentStore.StoredDocument(newTier.document(), newTier.searchableText(), List.of(1f, 2f)));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.replacePolicy(10, badReplacement, "text-embedding-3-small")))
                .rootCause().isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForList("SELECT document_id FROM benefit_search_document WHERE benefitcarrierpolicyid=10 ORDER BY document_id", String.class))
                .containsExactly("benefit:1:policy:10:tier:101", "benefit:1:policy:10:tier:102");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benefit_search_document WHERE benefitcarrierpolicyid=20", Integer.class)).isEqualTo(1);
    }

    @Test
    void realMigrationStoresOfferGradeDocumentsAndRejectsTierFromWrongPolicyAndNoncanonicalIdentity() {
        var offer = pending(offerDocument("benefit:1:policy:20:offer:vip-rate:grade:SKT_VIP", 1, 20,
                "vip-rate", "SKT_VIP"), "VIP 영화 개별 혜택");
        sourceSnapshots.put(20L, List.of(offer));
        transactions.executeWithoutResult(status -> store.replacePolicy(20, List.of(stored(offer, null)), "text-embedding-3-small"));

        assertThat(jdbc.queryForObject("SELECT offer_key FROM benefit_search_document WHERE document_id=?", String.class,
                offer.document().getDocumentId())).isEqualTo("vip-rate");
        assertThat(jdbc.queryForObject("SELECT metadata->>'offerKey' FROM benefit_search_document WHERE document_id=?", String.class,
                offer.document().getDocumentId())).isEqualTo("vip-rate");
        assertThat(new PostgresBenefitSearchRepository(jdbc, settings())
                .searchLexical("개별 혜택", Carrier.SKT, Grade.SKT_VIP, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::tierBenefitId).containsNull();

        var wrongPolicyTier = pending(document("benefit:1:policy:20:tier:101", 1, 20, 101L, "SKT_VIP", false, "wrong-policy"), "VIP 영화");
        sourceSnapshots.put(20L, List.of(wrongPolicyTier));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                store.replacePolicy(20, List.of(stored(wrongPolicyTier, null)), "text-embedding-3-small")))
                .rootCause().hasMessageContaining("fk_bsd_tier_policy_grade");
        assertThat(jdbc.queryForList("SELECT document_id FROM benefit_search_document WHERE benefitcarrierpolicyid=20", String.class))
                .containsExactly(offer.document().getDocumentId());

        assertThatThrownBy(() -> jdbc.update("UPDATE benefit_search_document SET document_id='wrong-id' WHERE document_id=?",
                offer.document().getDocumentId())).rootCause().hasMessageContaining("ck_bsd_document_identity");
    }

    @Test
    void variableDimensionMigrationStoresAndQueriesQwenVectorsWithoutMixing1536Model() {
        var offer = pending(offerDocument("benefit:1:policy:20:offer:qwen:grade:SKT_VIP", 1, 20,
                "qwen", "SKT_VIP"), "Qwen dimensions test");
        sourceSnapshots.put(20L, List.of(offer));
        var qwen = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("ollama", "qwen3-embedding:4b-q8_0", 2560,
                "http://127.0.0.1:11434");
        var qwenStore = new PostgresBenefitDocumentStore(jdbc, new ObjectMapper(), source, qwen);
        transactions.executeWithoutResult(status -> qwenStore.replacePolicy(20, List.of(
                new PostgresBenefitDocumentStore.StoredDocument(offer.document(), offer.searchableText(), vector(2560))
                ), qwen.modelIdentity()));

        assertThat(qwenStore.findEmbedding(offer.document().getDocumentId()).dimensions()).isEqualTo(2560);
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM benefit_search_document WHERE document_id=?", Integer.class,
                offer.document().getDocumentId())).isEqualTo(2560);
        var qwenSearch = new PostgresBenefitSearchRepository(jdbc, qwen);
        assertThat(qwenSearch.searchVector(vector(2560), Carrier.SKT, Grade.SKT_VIP, BenefitSearchCondition.none(), 5))
                .extracting(BenefitSearchHit::benefitId).containsExactly(1L);
        assertThatThrownBy(() -> new PostgresBenefitSearchRepository(jdbc, settings())
                .searchVector(vector(2560), Carrier.SKT, Grade.SKT_VIP, BenefitSearchCondition.none(), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE benefit_search_document SET embedding_dimensions=1536 WHERE document_id=?",
                offer.document().getDocumentId())).rootCause().satisfies(cause -> {
            assertThat(cause).isInstanceOf(SQLException.class);
            SQLException sqlException = (SQLException) cause;
            assertThat(sqlException.getSQLState()).isEqualTo("23514");
            assertThat(sqlException.getMessage()).contains("ck_bsd_embedding_dimensions");
        });
    }

    private static com.itplace.userapi.ai.rag.service.EmbeddingSettings settings() {
        return new com.itplace.userapi.ai.rag.service.EmbeddingSettings("openai", "text-embedding-3-small", 1536, "https://api.openai.com");
    }

    private static BenefitRagDocumentBuilder.PendingBenefitDocument pending(BenefitDocument document, String text) {
        return new BenefitRagDocumentBuilder.PendingBenefitDocument(document, text);
    }

    private static PostgresBenefitDocumentStore.StoredDocument stored(BenefitRagDocumentBuilder.PendingBenefitDocument pending,
                                                                        List<Float> embedding) {
        return new PostgresBenefitDocumentStore.StoredDocument(pending.document(), pending.searchableText(), embedding);
    }

    private static BenefitDocument document(String id, long benefitId, long policyId, Long tierId,
                                            String grade, boolean allGrade, String marker) {
        return BenefitDocument.builder().id(id).documentId(id).benefitId(Long.toString(benefitId))
                .policyId(Long.toString(policyId)).tierBenefitId(tierId == null ? null : Long.toString(tierId))
                .carrier("SKT").grade(grade).isAllGrade(allGrade).usageType("online").active(true)
                .embeddingVersion("benefit-carrier-grade-rag-v3").contentHash(marker.repeat(64).substring(0, 64))
                .sourceEvidenceId(77L).sourceUrl("https://example.test/benefit").sourceCapturedAt("2026-10-20T00:00:00Z")
                .description("영화 할인 혜택").tags(List.of("영화")).build();
    }

    private static BenefitDocument offerDocument(String id, long benefitId, long policyId, String offerKey, String grade) {
        return BenefitDocument.builder().id(id).documentId(id).benefitId(Long.toString(benefitId))
                .policyId(Long.toString(policyId)).offerKey(offerKey).carrier("SKT").grade(grade)
                .isAllGrade(false).usageType("online").active(true).embeddingVersion("benefit-carrier-grade-rag-v3")
                .contentHash("a".repeat(64)).sourceEvidenceId(77L).description("영화 할인 혜택")
                .tags(List.of("영화")).build();
    }

    private static List<Float> vector(float first) {
        List<Float> values = new ArrayList<>(Collections.nCopies(1536, 0f));
        values.set(0, first);
        return values;
    }

    private static List<Float> vector(int dimensions) {
        List<Float> values = new ArrayList<>(Collections.nCopies(dimensions, 0f));
        values.set(0, 1f);
        return values;
    }

    private static String vectorLiteral(int dimensions) {
        List<Float> values = new ArrayList<>(Collections.nCopies(dimensions, 0f));
        values.set(0, 1f);
        return "[" + String.join(",", values.stream().map(String::valueOf).toList()) + "]";
    }
}
