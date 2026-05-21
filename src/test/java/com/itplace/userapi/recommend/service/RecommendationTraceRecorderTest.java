package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.RecommendationRankTraceEntity;
import com.itplace.userapi.recommend.repository.RecommendationRankTraceRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationTraceRecorderTest {

    @Mock
    private RecommendationRankTraceRepository rankTraceRepository;

    @Captor
    private ArgumentCaptor<RecommendationRankTraceEntity> traceCaptor;

    @Test
    void recordGenerated_persistsAttributionCompleteRankTraceJson() {
        RecommendationTraceRecorder recorder = new RecommendationTraceRecorder(rankTraceRepository, new ObjectMapper().findAndRegisterModules());
        Candidate candidate = Candidate.builder()
                .benefitId(10L)
                .candidateSource("es_vector")
                .rankScore(15.0)
                .scoreComponents(Map.of("semantic_similarity", 0.7, "behavior_affinity", 1.0))
                .build();
        Recommendations shown = Recommendations.builder()
                .rank(1)
                .benefitIds(List.of(10L))
                .impressionId("imp-1")
                .candidateSource("es_vector")
                .fallbackFlags(List.of())
                .build();

        recorder.recordGenerated(
                7L,
                "req-1",
                "personalized-es-quality-v1",
                List.of(candidate),
                List.of(shown),
                Map.of("total", 12L),
                "miss",
                "expired_or_absent"
        );

        verify(rankTraceRepository).save(traceCaptor.capture());
        RecommendationRankTraceEntity saved = traceCaptor.getValue();
        assertThat(saved.getRequestId()).isEqualTo("req-1");
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getServiceType()).isEqualTo("personalized_recommendation");
        assertThat(saved.getAttributionComplete()).isTrue();
        assertThat(saved.getCacheStatus()).isEqualTo("miss");
        assertThat(saved.getTraceJson())
                .contains("\"candidateIds\":[10]")
                .contains("\"candidateSources\":[\"es_vector\"]")
                .contains("\"impressionIds\":[\"imp-1\"]");
    }
}
