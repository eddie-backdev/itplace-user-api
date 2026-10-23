package com.itplace.userapi.ai.rag.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.index.BenefitRagDocumentBuilder;
import com.itplace.userapi.ai.rag.index.BenefitRagSourceQueryService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresBenefitRagSyncServiceTest {
    @Mock BenefitRagSourceQueryService source;
    @Mock PostgresBenefitDocumentStore store;
    @Mock EmbeddingService embeddings;

    @Test
    void invalidActiveSourceBlocksAllEmbeddingCalls() {
        var pending = pending(true, null);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        var sync = new PostgresBenefitRagSyncService(source,store,embeddings,settings());
        String fingerprint=sync.dryRun().snapshotHash();

        assertThatThrownBy(() -> sync.syncValidatedSnapshot(fingerprint)).isInstanceOf(IllegalStateException.class);
        verify(embeddings,never()).embedBatch(anyList());
        verify(store,never()).replacePolicy(anyLong(),anyList(),any());
    }

    @Test
    void inactiveSourceIsStoredWithoutPaidEmbedding() {
        var pending = pending(false,1L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L),anyList())).thenReturn(true);
        var sync = new PostgresBenefitRagSyncService(source,store,embeddings,settings());

        var result = sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.written()).isEqualTo(1);
        verify(embeddings,never()).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L),anyList(),eq("text-embedding-3-small"));
    }

    @Test
    void staleSourcePreflightSkipsEmbedding() {
        var pending=pending(true,2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L),anyList())).thenReturn(false);
        var sync=new PostgresBenefitRagSyncService(source,store,embeddings,settings());
        var result=sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.stale()).isEqualTo(1);
        verify(embeddings,never()).embedBatch(anyList());
        verify(store,never()).replacePolicy(anyLong(),anyList(),any());
    }

    @Test
    void incompleteEmbeddingBatchLeavesPolicyUntouched() {
        var pending=pending(true,2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L),anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of());
        var sync=new PostgresBenefitRagSyncService(source,store,embeddings,settings());
        var result=sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).hasSize(1).first().extracting(PostgresBenefitRagSyncService.PolicyFailure::policyId).isEqualTo(10L);
        verify(store,never()).replacePolicy(anyLong(),anyList(),any());
    }

    @Test
    void changedMetadataInvalidatesReviewedFingerprintBeforeEmbedding() {
        var original=pending(true,2L);
        var changed=pending(true,2L);
        changed.document().setBenefitName("다른 혜택");
        when(source.loadSourceSnapshotFromPrimary())
                .thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(original)),
                        new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(changed)));
        var sync=new PostgresBenefitRagSyncService(source,store,embeddings,settings());
        String reviewed=sync.dryRun().snapshotHash();

        assertThatThrownBy(() -> sync.syncValidatedSnapshot(reviewed)).isInstanceOf(IllegalStateException.class);
        verify(embeddings,never()).embedBatch(anyList());
    }

    @Test
    void lexicalStagingDoesNotCallEmbeddingProvider() {
        var pending=pending(true,2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L),anyList())).thenReturn(true);
        var sync=new PostgresBenefitRagSyncService(source,store,embeddings,settings());
        var result=sync.stageValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.written()).isEqualTo(1);
        verify(embeddings,never()).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L),argThat(docs -> docs.size()==1 && docs.get(0).embedding()==null),eq("text-embedding-3-small"));
    }

    @Test
    void paidBackfillRecomputesNullVectorFromPriorLexicalStage() {
        var pending=pending(true,2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1,List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L),anyList())).thenReturn(true);
        when(store.findEmbedding("benefit:1:policy:10:tier:policy")).thenReturn(new PostgresBenefitDocumentStore.ExistingEmbedding(
                null,"text-embedding-3-small",1536,"benefit-carrier-grade-rag-v3","a".repeat(64)));
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(vector()));
        var sync=new PostgresBenefitRagSyncService(source,store,embeddings,settings());

        var result=sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.written()).isEqualTo(1);
        verify(embeddings).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L),argThat(docs -> docs.size()==1 && docs.get(0).embedding()!=null),eq("text-embedding-3-small"));
    }

    @Test
    void embedsEachDistinctModelVersionHashOnceAcrossSameAndDifferentPolicies() {
        String hash = "b".repeat(64);
        String body = "같은 혜택 검색 문서";
        var first = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, body);
        var duplicateInPolicy = pending("benefit:1:policy:10:tier:102", "1", "10", "102", "benefit-carrier-grade-rag-v3", hash, body);
        var duplicateInOtherPolicy = pending("benefit:2:policy:20:tier:201", "2", "20", "201", "benefit-carrier-grade-rag-v3", hash, body);
        var snapshot = new BenefitRagSourceQueryService.SourceSnapshot(2, List.of(first, duplicateInPolicy, duplicateInOtherPolicy));
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(snapshot);
        when(store.isCurrentSnapshot(anyLong(), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(vector()));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        assertThat(result.written()).isEqualTo(3);
        assertThat(result.reusedEmbeddings()).isEqualTo(2);
        verify(embeddings, times(1)).embedBatch(argThat(texts -> texts.equals(List.of(body))));
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.size()==2 && docs.stream().allMatch(d -> d.embedding()!=null)), eq("text-embedding-3-small"));
        verify(store).replacePolicy(eq(20L), argThat(docs -> docs.size()==1 && docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
    }

    @Test
    void reusesExistingVectorAcrossPoliciesOnlyWhenProvenanceMatches() {
        String hash = "c".repeat(64);
        String body = "기존 검증된 임베딩";
        var first = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, body);
        var duplicate = pending("benefit:2:policy:20:tier:201", "2", "20", "201", "benefit-carrier-grade-rag-v3", hash, body);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(2, List.of(first, duplicate)));
        when(store.isCurrentSnapshot(anyLong(), anyList())).thenReturn(true);
        when(store.findEmbedding(first.document().getDocumentId())).thenReturn(new PostgresBenefitDocumentStore.ExistingEmbedding(
                vectorLiteral(), "text-embedding-3-small", 1536, "benefit-carrier-grade-rag-v3", hash));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.reusedEmbeddings()).isEqualTo(2);
        verify(embeddings, never()).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
        verify(store).replacePolicy(eq(20L), argThat(docs -> docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
    }

    @Test
    void lexicalStageReusesMatchingExistingVectorAcrossDuplicateDocumentsWithoutEmbedding() {
        String hash = "9".repeat(64);
        String body = "스테이징 기존 벡터";
        var first = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, body);
        var duplicate = pending("benefit:2:policy:20:tier:201", "2", "20", "201", "benefit-carrier-grade-rag-v3", hash, body);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(2, List.of(first, duplicate)));
        when(store.isCurrentSnapshot(anyLong(), anyList())).thenReturn(true);
        when(store.findEmbedding(first.document().getDocumentId())).thenReturn(new PostgresBenefitDocumentStore.ExistingEmbedding(
                vectorLiteral(), "text-embedding-3-small", 1536, "benefit-carrier-grade-rag-v3", hash));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.stageValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.reusedEmbeddings()).isEqualTo(2);
        verify(embeddings, never()).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.size()==1 && docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
        verify(store).replacePolicy(eq(20L), argThat(docs -> docs.size()==1 && docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
    }

    @Test
    void lexicalStageCountsEarlierSamePolicyDocumentFilledFromLaterMatchingVector() {
        String hash = "8".repeat(64);
        String body = "같은 정책 중복 문서";
        var first = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, body);
        var laterWithVector = pending("benefit:1:policy:10:tier:102", "1", "10", "102", "benefit-carrier-grade-rag-v3", hash, body);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(first, laterWithVector)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(store.findEmbedding(first.document().getDocumentId())).thenReturn(null);
        when(store.findEmbedding(laterWithVector.document().getDocumentId())).thenReturn(new PostgresBenefitDocumentStore.ExistingEmbedding(
                vectorLiteral(), "text-embedding-3-small", 1536, "benefit-carrier-grade-rag-v3", hash));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.stageValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        assertThat(result.written()).isEqualTo(2);
        assertThat(result.reusedEmbeddings()).isEqualTo(2);
        verify(embeddings, never()).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.size()==2 && docs.stream().allMatch(d -> d.embedding()!=null)), eq("text-embedding-3-small"));
    }

    @Test
    void differingModelVersionOrContentHashNeverReusesExistingVector() {
        String hash = "d".repeat(64);
        var wrongModel = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, "같은 본문");
        var wrongVersion = pending("benefit:1:policy:10:tier:102", "1", "10", "102", "benefit-carrier-grade-rag-v4", hash, "같은 본문");
        var wrongHash = pending("benefit:1:policy:10:tier:103", "1", "10", "103", "benefit-carrier-grade-rag-v3", "e".repeat(64), "같은 본문");
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(wrongModel, wrongVersion, wrongHash)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(store.findEmbedding(wrongModel.document().getDocumentId())).thenReturn(existing("other-model", "benefit-carrier-grade-rag-v3", hash));
        when(store.findEmbedding(wrongVersion.document().getDocumentId())).thenReturn(existing("text-embedding-3-small", "benefit-carrier-grade-rag-v3", hash));
        when(store.findEmbedding(wrongHash.document().getDocumentId())).thenReturn(existing("text-embedding-3-small", "benefit-carrier-grade-rag-v3", hash));
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(vector(), vector(), vector()));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        verify(embeddings).embedBatch(argThat(texts -> texts.size()==3));
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.size()==3 && docs.stream().allMatch(d -> d.embedding()!=null)), eq("text-embedding-3-small"));
    }

    @Test
    void partialInvalidBatchDoesNotPopulateRunCacheForAnotherPolicy() {
        String hash = "f".repeat(64);
        String body = "실패한 중복 입력";
        var first = pending("benefit:1:policy:10:tier:101", "1", "10", "101", "benefit-carrier-grade-rag-v3", hash, body);
        var malformedSibling = pending("benefit:1:policy:10:tier:102", "1", "10", "102", "benefit-carrier-grade-rag-v3", "g".repeat(64), "다른 입력");
        var second = pending("benefit:2:policy:20:tier:201", "2", "20", "201", "benefit-carrier-grade-rag-v3", hash, body);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(2, List.of(first, malformedSibling, second)));
        when(store.isCurrentSnapshot(anyLong(), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(vector(), List.of(1f, 2f)), List.of(vector()));
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings());

        var result = sync.syncValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).hasSize(1).first().extracting(PostgresBenefitRagSyncService.PolicyFailure::policyId).isEqualTo(10L);
        assertThat(result.written()).isEqualTo(1);
        verify(embeddings).embedBatch(argThat(texts -> texts.equals(List.of(body, "다른 입력"))));
        verify(embeddings).embedBatch(argThat(texts -> texts.equals(List.of(body))));
        verify(store, never()).replacePolicy(eq(10L), anyList(), any());
        verify(store).replacePolicy(eq(20L), argThat(docs -> docs.size()==1 && docs.get(0).embedding()!=null), eq("text-embedding-3-small"));
    }

    @Test
    void qwenDimensionsAndProviderIdentityFlowIntoStoredPolicy() {
        var pending = pending(true, 2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(java.util.Collections.nCopies(2560, 0.25f)));
        var qwen = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("ollama", "qwen3-embedding:4b-q8_0", 2560, "http://127.0.0.1:11434");
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, qwen);

        var result = sync.embedLocalValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        assertThat(result.written()).isEqualTo(1);
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.get(0).embedding().size() == 2560), eq("ollama:qwen3-embedding:4b-q8_0"));
    }

    @Test
    void localKureDimensionsAndPinnedIdentityFlowIntoStoredPolicy() {
        var pending = pending(true, 2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(java.util.Collections.nCopies(1024, 0.25f)));
        var kure = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("local",
                com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL, 1024, "http://127.0.0.1:18081");
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, kure);

        var result = sync.embedLocalValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        assertThat(result.written()).isEqualTo(1);
        verify(store).replacePolicy(eq(10L), argThat(docs -> docs.get(0).embedding().size() == 1024),
                eq("local:" + com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL));
    }

    @Test
    void localKureAllowsExplicitlyTrustedContainerHost() {
        var pending = pending(true, 2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(java.util.Collections.nCopies(1024, 0.25f)));
        var kure = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("local",
                com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL, 1024,
                "http://kure-embedding:18081", "kure-embedding");
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, kure);

        var result = sync.embedLocalValidatedSnapshot(sync.dryRun().snapshotHash());

        assertThat(result.failures()).isEmpty();
        verify(embeddings).embedBatch(anyList());
    }

    @Test
    void postgresScheduledSyncUsesLocalEmbeddingWhenEnabled() {
        var pending = pending(true, 2L);
        when(source.loadSourceSnapshotFromPrimary()).thenReturn(
                new BenefitRagSourceQueryService.SourceSnapshot(1, List.of(pending)));
        when(store.isCurrentSnapshot(eq(10L), anyList())).thenReturn(true);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(java.util.Collections.nCopies(1024, 0.25f)));
        var settings = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("local",
                com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL, 1024,
                "http://127.0.0.1:18081");
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, settings);
        org.springframework.test.util.ReflectionTestUtils.setField(sync, "searchProvider", "postgres");
        org.springframework.test.util.ReflectionTestUtils.setField(sync, "syncEnabled", true);

        sync.scheduledSync();

        verify(embeddings).embedBatch(anyList());
        verify(store).replacePolicy(eq(10L), anyList(), eq("local:" +
                com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL));
    }

    @Test
    void localEmbedModeRejectsRemoteProviderBeforeLoadingOrEmbeddingSnapshot() {
        var remote = new com.itplace.userapi.ai.rag.service.EmbeddingSettings("ollama", "qwen3-embedding:4b-q8_0", 2560, "https://ollama.example");
        var sync = new PostgresBenefitRagSyncService(source, store, embeddings, remote);

        assertThatThrownBy(() -> sync.embedLocalValidatedSnapshot("a".repeat(64)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
        verify(source, never()).loadSourceSnapshotFromPrimary();
        verify(embeddings, never()).embedBatch(anyList());
    }

    @Test
    void localEmbedModeRejectsRemoteKureEndpointBeforeLoadingSnapshot() {
        assertThatThrownBy(() -> new com.itplace.userapi.ai.rag.service.EmbeddingSettings("local",
                com.itplace.userapi.ai.rag.service.EmbeddingSettings.KURE_MODEL, 1024, "https://kure.example"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
        verify(source, never()).loadSourceSnapshotFromPrimary();
        verify(embeddings, never()).embedBatch(anyList());
    }

    private static com.itplace.userapi.ai.rag.service.EmbeddingSettings settings() {
        return new com.itplace.userapi.ai.rag.service.EmbeddingSettings("openai", "text-embedding-3-small", 1536, "https://api.openai.com");
    }

    private static List<Float> vector() {
        var values=new java.util.ArrayList<>(java.util.Collections.nCopies(1536,0f));
        values.set(0,1f); return values;
    }

    private static String vectorLiteral() {
        return "[" + String.join(",", vector().stream().map(String::valueOf).toList()) + "]";
    }

    private static PostgresBenefitDocumentStore.ExistingEmbedding existing(String model, String version, String hash) {
        return new PostgresBenefitDocumentStore.ExistingEmbedding(vectorLiteral(), model, 1536, version, hash);
    }

    private static BenefitRagDocumentBuilder.PendingBenefitDocument pending(boolean active, Long evidenceId) {
        var pending = pending("benefit:1:policy:10:tier:policy", "1", "10", null,
                "benefit-carrier-grade-rag-v3", "a".repeat(64), "영화 혜택: 영화관 할인");
        BenefitDocument document = pending.document();
        document.setActive(active);
        document.setSourceEvidenceId(evidenceId);
        document.setSourceUrl(evidenceId==null?null:"https://example.test/source");
        return pending;
    }

    private static BenefitRagDocumentBuilder.PendingBenefitDocument pending(String documentId, String benefitId,
            String policyId, String tierBenefitId, String version, String contentHash, String text) {
        BenefitDocument document=BenefitDocument.builder().documentId(documentId).id(documentId)
                .benefitId(benefitId).policyId(policyId).tierBenefitId(tierBenefitId).carrier("SKT").active(true)
                .sourceUrl("https://example.test/source").sourceCapturedAt("2026-10-19T00:00:00Z")
                .sourceEvidenceId(2L).sourceCategory("문화/여가").description("영화관 할인")
                .businessType("MOVIE_THEATER").tags(List.of("MOVIE_THEATER"))
                .embeddingVersion(version).contentHash(contentHash).build();
        return new BenefitRagDocumentBuilder.PendingBenefitDocument(document,text);
    }
}
