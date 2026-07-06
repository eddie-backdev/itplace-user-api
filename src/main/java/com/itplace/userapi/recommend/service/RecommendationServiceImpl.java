package com.itplace.userapi.recommend.service;


import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.mapper.RecommendationMapper;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    private static final int EXPIRED_DAYS = 1;
    private static final int MIN_CANDIDATE_SIZE = 10;
    private static final int MAX_CANDIDATE_SIZE = 20;
    private static final String ALGORITHM_VERSION = "personalized-es-quality-v1";
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
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;
    private final LogRepository logRepository;
    private final FavoriteRepository favoriteRepository;
    private final RecommendationTraceRecorder traceRecorder;

    @Transactional
    public List<Recommendations> recommend(Long userId, int topK) {
        long startedAt = System.nanoTime();
        String requestId = newRequestId(userId);
        LocalDateTime threshold = LocalDateTime.now().minusDays(EXPIRED_DAYS); // n일 기준으로 추천 갱신

        // 최근 추천 기록 있으면 동일 batch 안의 active 추천만 반환한다.
        Recommendation latestRecommendation = recommendationRepository
                .findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(userId, threshold)
                .orElse(null);
        if (latestRecommendation != null && !hasInvalidatingSignalAfter(userId, latestRecommendation.getCreatedDate())) {
            List<Recommendation> saved = recommendationRepository
                    .findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(
                            userId, latestRecommendation.getCacheBatchId());
            if (!saved.isEmpty()) {
                List<Recommendations> cached = RecommendationMapper.toDtoList(saved);
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
        }

        // 사용자 성향 정보 로딩
        UserFeature uf = userFeatureService.loadUserFeature(userId);

        // 벡터 검색 기반 추천 후보
        List<Candidate> candidates = aiService.vectorSearch(uf, candidateSize(topK));
        // 재랭킹 및 이유 생성
        List<Recommendations> recommendations = aiService.rerankAndExplain(uf, candidates, topK);
        attachRequestAttribution(userId, recommendations, requestId, ALGORITHM_VERSION, List.of());

        // 저장
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Long> allBenefitIds = recommendations.stream()
                .flatMap(dto -> benefitIdsOf(dto).stream())
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Benefit> benefitMap = benefitRepository.findAllById(allBenefitIds).stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, b -> b));

        String cacheBatchId = newCacheBatchId(userId);
        List<Recommendation> entities = recommendations.stream()
                .map(dto -> {
                    List<Benefit> benefits = benefitIdsOf(dto).stream()
                            .map(benefitMap::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    return RecommendationMapper.toEntity(dto, user, benefits, cacheBatchId, ALGORITHM_VERSION);
                })
                .toList();

        recommendationRepository.deactivateActiveByUserId(userId);
        recommendationRepository.saveAll(entities);
        traceRecorder.recordGenerated(
                userId,
                requestId,
                ALGORITHM_VERSION,
                candidates,
                recommendations,
                Map.of("total", elapsedMs(startedAt)),
                latestRecommendation == null ? "miss" : "refresh",
                latestRecommendation == null ? "expired_or_absent" : "invalidating_signal"
        );

        return recommendations;
    }

    private List<Long> benefitIdsOf(Recommendations dto) {
        if (dto.getBenefitIds() == null) {
            return List.of();
        }

        return dto.getBenefitIds();
    }

    boolean hasInvalidatingSignalAfter(Long userId, LocalDateTime latestRecommendationDate) {
        boolean hasRecentLog = logRepository.findLatestLoggingAtByEvents(userId, CACHE_INVALIDATING_EVENTS)
                .map(this::toLocalDateTime)
                .map(latestEventAt -> latestEventAt.isAfter(latestRecommendationDate))
                .orElse(false);
        if (hasRecentLog) {
            return true;
        }

        boolean hasRecentFavorite = favoriteRepository.existsByUserIdAndCreatedDateAfter(userId, latestRecommendationDate);
        if (hasRecentFavorite) {
            return true;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        LocalDateTime profileUpdatedAt = user.getLastModifiedDate();
        return profileUpdatedAt != null && profileUpdatedAt.isAfter(latestRecommendationDate);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private int candidateSize(int topK) {
        return Math.min(Math.max(topK * 3, MIN_CANDIDATE_SIZE), MAX_CANDIDATE_SIZE);
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

    private String newCacheBatchId(Long userId) {
        return "rec-" + userId + "-" + UUID.randomUUID();
    }

    private String newRequestId(Long userId) {
        return "rec-req-" + userId + "-" + UUID.randomUUID();
    }

    private String newImpressionId(Long userId, int rank) {
        return "rec-imp-" + userId + "-" + rank + "-" + UUID.randomUUID();
    }

}
