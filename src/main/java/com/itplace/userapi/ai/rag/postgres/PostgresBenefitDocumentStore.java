package com.itplace.userapi.ai.rag.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.index.BenefitRagDocumentBuilder;
import com.itplace.userapi.ai.rag.index.BenefitRagSourceQueryService;
import com.itplace.userapi.ai.rag.service.EmbeddingSettings;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Repository
public class PostgresBenefitDocumentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BenefitRagSourceQueryService source;
    private final EmbeddingSettings embeddingSettings;

    public PostgresBenefitDocumentStore(JdbcTemplate jdbc, ObjectMapper mapper, BenefitRagSourceQueryService source,
                                        EmbeddingSettings embeddingSettings) {
        this.jdbc = jdbc; this.mapper = mapper; this.source = source; this.embeddingSettings = embeddingSettings;
    }

    public ExistingEmbedding findEmbedding(String documentId) {
        return jdbc.query("SELECT embedding::text, embedding_model, embedding_dimensions, embedding_version, content_hash " +
                        "FROM benefit_search_document WHERE document_id=?", rs -> rs.next()
                ? new ExistingEmbedding(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5).trim())
                : null, documentId);
    }

    /** Cheap preflight avoids embedding a policy already changed after the source snapshot. */
    public boolean isCurrentSnapshot(long policyId, List<BenefitRagDocumentBuilder.PendingBenefitDocument> expected) {
        List<BenefitRagDocumentBuilder.PendingBenefitDocument> current = source.loadPolicySnapshotFromPrimary(policyId);
        Map<String,BenefitDocument> currentDocuments = current.stream().collect(Collectors.toMap(
                p -> p.document().getDocumentId(), BenefitRagDocumentBuilder.PendingBenefitDocument::document));
        return currentDocuments.size() == expected.size() && expected.stream().allMatch(p -> {
            BenefitDocument now = currentDocuments.get(p.document().getDocumentId());
            return now != null && now.getContentHash().equals(p.document().getContentHash())
                    && mapper.valueToTree(now).equals(mapper.valueToTree(p.document()));
        });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void replacePolicy(long policyId, List<StoredDocument> documents, String model) {
        if (!embeddingSettings.modelIdentity().equals(model))
            throw new IllegalArgumentException("Embedding model does not match the configured provider model");
        if (documents.isEmpty()) throw new IllegalArgumentException("Refusing to replace policy with an empty snapshot");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('benefit-rag:' || ?, 0))", Object.class,
                Long.toString(policyId));
        Long benefitId = jdbc.query("SELECT benefitId FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, policyId);
        Long locked = benefitId == null ? null : jdbc.query("SELECT benefitId FROM benefit WHERE benefitId=? FOR SHARE",
                rs -> rs.next() ? rs.getLong(1) : null, benefitId);
        if (locked == null) throw new StaleSnapshotException();
        Long partnerId = jdbc.query("SELECT partnerId FROM benefit WHERE benefitId=?",
                rs -> rs.next() ? rs.getLong(1) : null, benefitId);
        if (partnerId != null) jdbc.query("SELECT partnerId FROM partner WHERE partnerId=? FOR SHARE",
                rs -> rs.next() ? rs.getLong(1) : null, partnerId);
        jdbc.query("SELECT carrierTierBenefitId FROM carrierTierBenefit WHERE benefitCarrierPolicyId=? FOR SHARE",
                rs -> { while (rs.next()) { /* hold source-row locks until policy replacement commits */ } return null; }, policyId);

        List<BenefitRagDocumentBuilder.PendingBenefitDocument> current = source.loadPolicySnapshotFromPrimary(policyId);
        if (!sameSnapshot(current, documents.stream().map(StoredDocument::document).toList())) {
            throw new StaleSnapshotException();
        }

        jdbc.update("DELETE FROM benefit_search_document WHERE benefitcarrierpolicyid=?", policyId);
        for (StoredDocument stored : documents) insert(stored, model);
    }

    private boolean sameSnapshot(List<BenefitRagDocumentBuilder.PendingBenefitDocument> current,
                                 List<BenefitDocument> expected) {
        Map<String,BenefitDocument> byId = current.stream().collect(Collectors.toMap(
                p -> p.document().getDocumentId(), BenefitRagDocumentBuilder.PendingBenefitDocument::document));
        return byId.size() == expected.size() && expected.stream().allMatch(d -> {
            BenefitDocument now=byId.get(d.getDocumentId());
            return now!=null && now.getContentHash().equals(d.getContentHash())
                    && mapper.valueToTree(now).equals(mapper.valueToTree(d));
        });
    }

    private void insert(StoredDocument stored, String model) {
        BenefitDocument d = stored.document();
        try {
            ObjectNode metadata = mapper.valueToTree(d);
            metadata.remove("embedding");
            jdbc.update("""
                    INSERT INTO benefit_search_document(document_id, benefitid, benefitcarrierpolicyid, carriertierbenefitid,
                        offer_key, carrier, grade, is_all_grade, usage_type, active, searchable_text, metadata, embedding,
                        embedding_model, embedding_dimensions, embedding_version, content_hash, source_updated_at, indexed_at, deleted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?, ?, ?, ?::timestamptz, now(), ?::timestamptz)
                    """, d.getDocumentId(), Long.valueOf(d.getBenefitId()), Long.valueOf(d.getPolicyId()),
                    d.getTierBenefitId() == null ? null : Long.valueOf(d.getTierBenefitId()), d.getOfferKey(), d.getCarrier(), d.getGrade(),
                    Boolean.TRUE.equals(d.getIsAllGrade()), d.getUsageType(), !Boolean.FALSE.equals(d.getActive()),
                    stored.searchableText(), mapper.writeValueAsString(metadata), vectorLiteral(stored.embedding()), model,
                    embeddingSettings.dimensions(),
                    d.getEmbeddingVersion(), d.getContentHash(), d.getSourceUpdatedAt(), d.getDeletedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize benefit document", e);
        }
    }

    private String vectorLiteral(List<Float> vector) {
        if (vector == null) return null;
        if (vector.size() != embeddingSettings.dimensions() || vector.stream().anyMatch(v -> v == null || !Float.isFinite(v)))
            throw new IllegalArgumentException("Expected a finite embedding");
        double norm = vector.stream().mapToDouble(v -> v.doubleValue() * v.doubleValue()).sum();
        if (norm == 0) throw new IllegalArgumentException("Embedding must be nonzero");
        return "[" + String.join(",", vector.stream().map(String::valueOf).toList()) + "]";
    }

    private static String vectorLiteral(String literal) { return literal; }

    public record ExistingEmbedding(String vector, String model, int dimensions, String version, String contentHash) {}
    public record StoredDocument(BenefitDocument document, String searchableText, List<Float> embedding) {}
    public static final class StaleSnapshotException extends RuntimeException {}
}
