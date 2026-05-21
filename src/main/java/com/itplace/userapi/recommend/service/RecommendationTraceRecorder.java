package com.itplace.userapi.recommend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.RecommendationRankTraceEntity;
import com.itplace.userapi.recommend.repository.RecommendationRankTraceRepository;
import com.itplace.userapi.recommend.trace.RecommendationRankTrace;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationTraceRecorder {
    static final String SERVICE_TYPE = "personalized_recommendation";
    static final String EXPERIMENT_ARM = "personalized_es_quality_v1";

    private final RecommendationRankTraceRepository rankTraceRepository;
    private final ObjectMapper objectMapper;

    public void recordGenerated(Long userId,
                                String requestId,
                                String algorithmVersion,
                                List<Candidate> candidates,
                                List<Recommendations> shownRecommendations,
                                Map<String, Long> latencyMs,
                                String cacheStatus,
                                String invalidationReason) {
        List<Candidate> orderedCandidates = candidates.stream()
                .filter(candidate -> candidate.getBenefitId() != null)
                .sorted(Comparator
                        .comparing((Candidate candidate) -> candidate.getRankScore() == null ? 0.0 : candidate.getRankScore())
                        .reversed()
                        .thenComparing(candidate -> candidate.getBenefitId() == null ? Long.MAX_VALUE : candidate.getBenefitId()))
                .toList();

        List<Long> candidateIds = orderedCandidates.stream()
                .map(Candidate::getBenefitId)
                .toList();
        List<String> candidateSources = orderedCandidates.stream()
                .map(candidate -> textOrDefault(candidate.getCandidateSource(), "unknown"))
                .toList();
        Map<Long, Map<String, Double>> scoreComponents = new LinkedHashMap<>();
        orderedCandidates.forEach(candidate -> scoreComponents.put(
                candidate.getBenefitId(),
                candidate.getScoreComponents() == null ? Map.of() : candidate.getScoreComponents()));

        record(userId, requestId, algorithmVersion, candidateIds, candidateSources, scoreComponents,
                shownRecommendations, latencyMs, cacheStatus, invalidationReason);
    }

    public void recordCached(Long userId,
                             String requestId,
                             String algorithmVersion,
                             List<Recommendations> shownRecommendations,
                             Map<String, Long> latencyMs,
                             String invalidationReason) {
        List<Long> candidateIds = shownRecommendations.stream()
                .map(this::firstBenefitId)
                .filter(Objects::nonNull)
                .toList();
        List<String> candidateSources = shownRecommendations.stream()
                .filter(recommendation -> firstBenefitId(recommendation) != null)
                .map(recommendation -> textOrDefault(recommendation.getCandidateSource(), "cached_recommendation"))
                .toList();
        Map<Long, Map<String, Double>> scoreComponents = new LinkedHashMap<>();
        candidateIds.forEach(candidateId -> scoreComponents.put(candidateId, Map.of()));

        record(userId, requestId, algorithmVersion, candidateIds, candidateSources, scoreComponents,
                shownRecommendations, latencyMs, "hit", invalidationReason);
    }

    private void record(Long userId,
                        String requestId,
                        String algorithmVersion,
                        List<Long> candidateIds,
                        List<String> candidateSources,
                        Map<Long, Map<String, Double>> scoreComponents,
                        List<Recommendations> shownRecommendations,
                        Map<String, Long> latencyMs,
                        String cacheStatus,
                        String invalidationReason) {
        List<Long> shownIds = shownRecommendations.stream()
                .map(this::firstBenefitId)
                .filter(Objects::nonNull)
                .toList();
        List<String> impressionIds = shownRecommendations.stream()
                .filter(recommendation -> firstBenefitId(recommendation) != null)
                .map(Recommendations::getImpressionId)
                .toList();
        List<String> fallbackFlags = shownRecommendations.stream()
                .filter(recommendation -> firstBenefitId(recommendation) != null)
                .map(this::fallbackFlag)
                .toList();

        RecommendationRankTrace trace = RecommendationRankTrace.builder()
                .requestId(requestId)
                .serviceType(SERVICE_TYPE)
                .algorithmVersion(algorithmVersion)
                .experimentArm(EXPERIMENT_ARM)
                .candidateIds(candidateIds)
                .candidateSources(candidateSources)
                .scoreComponents(scoreComponents)
                .shownIds(shownIds)
                .impressionIds(impressionIds)
                .fallbackFlags(fallbackFlags)
                .latencyMs(latencyMs)
                .privacyFlags(Map.of("text_redacted", true, "geo_bucketed", true))
                .build();

        try {
            rankTraceRepository.save(RecommendationRankTraceEntity.builder()
                    .requestId(requestId)
                    .userId(userId)
                    .serviceType(SERVICE_TYPE)
                    .algorithmVersion(algorithmVersion)
                    .experimentArm(EXPERIMENT_ARM)
                    .cacheStatus(cacheStatus)
                    .invalidationReason(invalidationReason)
                    .attributionComplete(trace.isAttributionComplete())
                    .traceJson(objectMapper.writeValueAsString(trace))
                    .build());
        } catch (JsonProcessingException e) {
            log.warn("추천 rank trace 직렬화 실패: requestId={}, reason={}", requestId, e.getMessage());
        }
    }

    private Long firstBenefitId(Recommendations recommendation) {
        if (recommendation.getBenefitIds() == null || recommendation.getBenefitIds().isEmpty()) {
            return null;
        }
        return recommendation.getBenefitIds().get(0);
    }

    private String fallbackFlag(Recommendations recommendation) {
        if (recommendation.getFallbackFlags() == null || recommendation.getFallbackFlags().isEmpty()) {
            if ("db_fallback".equals(recommendation.getCandidateSource())) {
                return "db_fallback";
            }
            return "none";
        }
        return String.join("+", recommendation.getFallbackFlags());
    }

    private String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
