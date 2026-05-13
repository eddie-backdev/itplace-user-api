package com.itplace.userapi.recommend.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationTraceContractTest {

    @Test
    void canonicalEventRequiresAttributionFieldsForRecommendationChain() {
        CanonicalRecommendationEvent event = CanonicalRecommendationEvent.builder()
                .requestId("req-1")
                .impressionId("imp-1")
                .userIdHash("user-hash")
                .eventType(RecommendationEventType.RECOMMENDATION_IMPRESSION)
                .serviceType("personalized_recommendation")
                .sourceSurface("personalized_list")
                .algorithmVersion("es-quality-v1")
                .rank(1)
                .candidateSource("es_vector")
                .build();

        assertThat(event.hasRecommendationAttribution()).isTrue();
        assertThat(event.getSchemaVersion()).isEqualTo("recommendation-event.v1");
    }

    @Test
    void rankTraceRequiresCandidateSourcesAndScoreComponents() {
        RecommendationRankTrace trace = RecommendationRankTrace.builder()
                .requestId("req-1")
                .serviceType("personalized_recommendation")
                .candidateIds(List.of(1L, 2L))
                .candidateSources(List.of("es_vector", "favorite_neighbor"))
                .scoreComponents(Map.of(
                        1L, Map.of("behavior_affinity", 7.0),
                        2L, Map.of("semantic_similarity", 0.8)
                ))
                .shownIds(List.of(1L))
                .impressionIds(List.of("imp-1"))
                .fallbackFlags(List.of("none"))
                .latencyMs(Map.of("total", 10L))
                .privacyFlags(Map.of("text_redacted", true, "geo_bucketed", true))
                .build();

        assertThat(trace.isAttributionComplete()).isTrue();
        assertThat(trace.getAlgorithmVersion()).isEqualTo("es-quality-v1");
    }

    @Test
    void canonicalEventRejectsRecommendationImpressionWithoutImpressionRankAndCandidateSource() {
        CanonicalRecommendationEvent event = CanonicalRecommendationEvent.builder()
                .requestId("req-1")
                .userIdHash("user-hash")
                .eventType(RecommendationEventType.RECOMMENDATION_IMPRESSION)
                .serviceType("personalized_recommendation")
                .sourceSurface("personalized_list")
                .algorithmVersion("es-quality-v1")
                .build();

        assertThat(event.hasRecommendationAttribution()).isFalse();
    }

    @Test
    void canonicalEventRejectsRecommendationChainWithoutServiceType() {
        CanonicalRecommendationEvent event = CanonicalRecommendationEvent.builder()
                .requestId("req-1")
                .impressionId("imp-1")
                .userIdHash("user-hash")
                .eventType(RecommendationEventType.RECOMMENDATION_IMPRESSION)
                .sourceSurface("personalized_list")
                .algorithmVersion("es-quality-v1")
                .rank(1)
                .candidateSource("es_vector")
                .build();

        assertThat(event.hasRecommendationAttribution()).isFalse();
    }

    @Test
    void rankTraceRejectsMisalignedCandidateAndImpressionLists() {
        RecommendationRankTrace trace = RecommendationRankTrace.builder()
                .requestId("req-1")
                .serviceType("personalized_recommendation")
                .candidateIds(List.of(1L, 2L))
                .candidateSources(List.of("es_vector"))
                .scoreComponents(Map.of(1L, Map.of("behavior_affinity", 7.0)))
                .shownIds(List.of(1L))
                .impressionIds(List.of("imp-1"))
                .fallbackFlags(List.of())
                .latencyMs(Map.of("total", 10L))
                .privacyFlags(Map.of("text_redacted", true, "geo_bucketed", true))
                .build();

        assertThat(trace.isAttributionComplete()).isFalse();
    }

}
