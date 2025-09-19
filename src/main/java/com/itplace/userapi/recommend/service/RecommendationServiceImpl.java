package com.itplace.userapi.recommend.service;


import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.mapper.RecommendationMapper;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    private static final int EXPIRED_DAYS = 1;

    private final UserFeatureService userFeatureService;
    private final OpenAIService aiService;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;

    public List<Recommendations> recommend(Long userId, int topK) throws Exception {
        LocalDateTime threshold = LocalDateTime.now().minusDays(EXPIRED_DAYS); // n일 기준으로 추천 갱신

        // 최근 추천 기록 있으면 캐시된 추천 반환
        LocalDate latestRecommendationDate = recommendationRepository.findLatestRecommendationDate(userId, threshold);
        if (latestRecommendationDate != null) {
            List<Recommendation> saved = recommendationRepository
                    .findByUserIdAndCreatedDate(userId, latestRecommendationDate);
            if (!saved.isEmpty()) {
                return RecommendationMapper.toDtoList(saved);
            }
        }

        // 사용자 성향 정보 로딩
        UserFeature uf = userFeatureService.loadUserFeature(userId);

        // 벡터 검색 기반 추천 후보
        List<Candidate> candidates = aiService.vectorSearch(uf, 50);
        // 재랭킹 및 이유 생성
        List<Recommendations> recommendations = aiService.rerankAndExplain(uf, candidates, topK);

        // 저장
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Recommendation> entities = recommendations.stream()
                .map(dto -> {
                    List<Benefit> benefits = dto.getBenefitIds().stream()
                            .map(benefitRepository::getReferenceById)
                            .toList();
                    return RecommendationMapper.toEntity(dto, user, benefits);
                })
                .toList();

        recommendationRepository.saveAll(entities);

        return recommendations;
    }

}

