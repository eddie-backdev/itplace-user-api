package com.itplace.userapi.recommend.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.mapper.RecommendationMapper;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationPersistenceService {

    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;
    private final FavoriteRepository favoriteRepository;

    /**
     * Redis lock handoff 직후에도 replica 지연 없이 방금 저장된 batch를 확인해야 하므로 source DB를 사용한다.
     */
    @Transactional
    public Optional<CachedBatch> findLatestActiveBatch(Long userId, LocalDateTime threshold) {
        Recommendation latest = recommendationRepository
                .findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(userId, threshold)
                .orElse(null);
        if (latest == null) {
            return Optional.empty();
        }

        List<Recommendation> saved = recommendationRepository
                .findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(userId, latest.getCacheBatchId());
        if (saved.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new CachedBatch(latest.getCreatedDate(), RecommendationMapper.toDtoList(saved)));
    }

    @Transactional
    public boolean hasPersistentInvalidatingSignalAfter(Long userId, LocalDateTime latestRecommendationDate) {
        if (favoriteRepository.existsByUserIdAndCreatedDateAfter(userId, latestRecommendationDate)) {
            return true;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        LocalDateTime profileUpdatedAt = user.getLastModifiedDate();
        return profileUpdatedAt != null && profileUpdatedAt.isAfter(latestRecommendationDate);
    }

    @Transactional
    public void replaceActiveBatch(
            Long userId,
            List<Recommendations> recommendations,
            String algorithmVersion
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Long> benefitIds = recommendations.stream()
                .flatMap(recommendation -> benefitIdsOf(recommendation).stream())
                .distinct()
                .toList();
        Map<Long, Benefit> benefitById = benefitIds.isEmpty()
                ? Map.of()
                : benefitRepository.findAllById(benefitIds).stream()
                        .collect(Collectors.toMap(Benefit::getBenefitId, benefit -> benefit));

        String cacheBatchId = newCacheBatchId(userId);
        List<Recommendation> entities = recommendations.stream()
                .map(recommendation -> RecommendationMapper.toEntity(
                        recommendation,
                        user,
                        benefitIdsOf(recommendation).stream()
                                .map(benefitById::get)
                                .filter(Objects::nonNull)
                                .toList(),
                        cacheBatchId,
                        algorithmVersion
                ))
                .toList();

        recommendationRepository.deactivateActiveByUserId(userId);
        recommendationRepository.saveAll(entities);
    }

    private List<Long> benefitIdsOf(Recommendations recommendation) {
        return recommendation.getBenefitIds() == null ? List.of() : recommendation.getBenefitIds();
    }

    private String newCacheBatchId(Long userId) {
        return "rec-" + userId + "-" + UUID.randomUUID();
    }

    public record CachedBatch(LocalDateTime createdAt, List<Recommendations> recommendations) {
    }
}
