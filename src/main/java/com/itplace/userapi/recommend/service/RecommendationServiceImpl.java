package com.itplace.userapi.recommend.service;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.service.RecommendationPersistenceService.CachedBatch;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    private static final int EXPIRED_DAYS = 1;
    private static final String ALGORITHM_VERSION = "personalized-es-quality-v2";
    private static final int CANDIDATE_SIZE = 20;
    private static final List<String> CACHE_INVALIDATING_EVENTS = List.of(
            "click",
            "detail",
            "search",
            "recommendation_click",
            "search_result_click",
            "benefit_detail_view",
            "favorite_add",
            "favorite_remove",
            "benefit_use",
            "impression",
            "dismiss",
            "skip",
            "negative",
            "negative_feedback",
            "feedback_negative",
            "not_interested"
    );

    private final UserFeatureService userFeatureService;
    private final OpenAIService aiService;
    private final LogRepository logRepository;
    private final RecommendationPersistenceService persistenceService;
    private final RecommendationGenerationLock generationLock;
    private final RecommendationTraceRecorder traceRecorder;

    @Override
    public List<Recommendations> recommend(Long userId, int topK) {
        long startedAt = System.nanoTime();
        int requestedCount = Math.min(Math.max(topK, 1), RecommendationService.MAX_RECOMMENDATION_COUNT);
        String requestId = newRequestId(userId);
        LocalDateTime threshold = LocalDateTime.now().minusDays(EXPIRED_DAYS); // n일 기준으로 추천 갱신

        CacheLookup initialLookup = lookupCache(userId, threshold);
        if (initialLookup.usable()) {
            return returnCached(userId, requestId, startedAt, requestedCount, initialLookup.batch());
        }

        try (RecommendationGenerationLock.Lease ignored = generationLock.acquire(userId)) {
            // 대기 중 다른 요청이 저장했을 수 있으므로 source DB에서 다시 확인한다.
            CacheLookup lockedLookup = lookupCache(userId, threshold);
            if (lockedLookup.usable()) {
                return returnCached(userId, requestId, startedAt, requestedCount, lockedLookup.batch());
            }

            UserFeature userFeature = userFeatureService.loadUserFeature(userId);
            List<Candidate> candidates = aiService.vectorSearch(userFeature, CANDIDATE_SIZE);
            List<Recommendations> generated = aiService.rerankAndExplain(
                    userFeature, candidates, RecommendationService.MAX_RECOMMENDATION_COUNT)
                    .stream().limit(RecommendationService.MAX_RECOMMENDATION_COUNT).toList();

            List<Recommendations> recommendations = generated.stream().limit(requestedCount).toList();
            attachRequestAttribution(userId, recommendations, requestId, ALGORITHM_VERSION, List.of());
            persistenceService.replaceActiveBatch(userId, generated, ALGORITHM_VERSION);
            traceRecorder.recordGenerated(
                    userId,
                    requestId,
                    ALGORITHM_VERSION,
                    candidates,
                    recommendations,
                    Map.of("total", elapsedMs(startedAt)),
                    lockedLookup.batch() == null ? "miss" : "refresh",
                    lockedLookup.batch() == null ? "expired_or_absent" : "invalidating_signal"
            );
            return recommendations;
        }
    }

    private CacheLookup lookupCache(Long userId, LocalDateTime threshold) {
        Optional<CachedBatch> batch = persistenceService.findLatestActiveBatch(userId, threshold);
        if (batch.isEmpty()) {
            return new CacheLookup(null, false);
        }
        boolean currentVersion = ALGORITHM_VERSION.equals(batch.get().algorithmVersion());
        return new CacheLookup(batch.get(), currentVersion
                && !hasInvalidatingSignalAfter(userId, batch.get().createdAt()));
    }

    private List<Recommendations> returnCached(
            Long userId,
            String requestId,
            long startedAt,
            int requestedCount,
            CachedBatch batch
    ) {
        List<Recommendations> cached = batch.recommendations().stream().limit(requestedCount).toList();
        attachRequestAttribution(userId, cached, requestId, ALGORITHM_VERSION, List.of("cached_recommendation"));
        traceRecorder.recordCached(
                userId,
                requestId,
                ALGORITHM_VERSION,
                cached,
                Map.of("total", elapsedMs(startedAt)),
                "none"
        );
        return cached;
    }

    boolean hasInvalidatingSignalAfter(Long userId, LocalDateTime latestRecommendationDate) {
        boolean hasRecentLog = logRepository.findLatestLoggingAtByEvents(userId, CACHE_INVALIDATING_EVENTS)
                .map(this::toLocalDateTime)
                .map(latestEventAt -> latestEventAt.isAfter(latestRecommendationDate))
                .orElse(false);
        if (hasRecentLog) {
            return true;
        }

        return persistenceService.hasPersistentInvalidatingSignalAfter(userId, latestRecommendationDate);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private void attachRequestAttribution(Long userId,
                                          List<Recommendations> recommendations,
                                          String requestId,
                                          String algorithmVersion,
                                          List<String> additionalFallbackFlags) {
        for (Recommendations recommendation : recommendations) {
            recommendation.setRequestId(requestId);
            recommendation.setImpressionId(newImpressionId(userId, recommendation.getRank()));
            recommendation.setAlgorithmVersion(algorithmVersion);
            if (recommendation.getCandidateSource() == null || recommendation.getCandidateSource().isBlank()) {
                recommendation.setCandidateSource(additionalFallbackFlags.contains("cached_recommendation")
                        ? "cached_recommendation"
                        : "unknown");
            }
            if (!additionalFallbackFlags.isEmpty()) {
                recommendation.setFallbackFlags(additionalFallbackFlags);
            } else if (recommendation.getFallbackFlags() == null) {
                recommendation.setFallbackFlags(List.of());
            }
        }
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String newRequestId(Long userId) {
        return "rec-req-" + userId + "-" + UUID.randomUUID();
    }

    private String newImpressionId(Long userId, int rank) {
        return "rec-imp-" + userId + "-" + rank + "-" + UUID.randomUUID();
    }

    private record CacheLookup(CachedBatch batch, boolean usable) {
    }

}
