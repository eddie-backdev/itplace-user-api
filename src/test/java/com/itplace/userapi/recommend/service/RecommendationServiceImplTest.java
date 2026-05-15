package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceImplTest {

    @Mock
    private UserFeatureService userFeatureService;

    @Mock
    private OpenAIService aiService;

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private LogRepository logRepository;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Captor
    private ArgumentCaptor<List<Recommendation>> recommendationsCaptor;

    @InjectMocks
    private RecommendationServiceImpl recommendationService;

    @Test
    void recommend_doesNotPersistNullBenefitsWhenGeneratedBenefitIdsAreStale() {
        UserFeature userFeature = UserFeature.builder()
                .userId(7L)
                .topCategories(List.of())
                .recentPartnerNames(List.of())
                .clickPartners(List.of())
                .searchPartners(List.of())
                .detailPartners(List.of())
                .build();
        Recommendations generated = Recommendations.builder()
                .rank(1)
                .partnerName("파트너")
                .reason("추천 이유")
                .imgUrl("image")
                .benefitIds(List.of(1L, 999L))
                .build();
        Benefit existingBenefit = Benefit.builder()
                .benefitId(1L)
                .benefitName("존재하는 혜택")
                .build();
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();

        when(recommendationRepository
                .findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(userFeatureService.loadUserFeature(7L)).thenReturn(userFeature);
        when(aiService.vectorSearch(userFeature, 10)).thenReturn(List.<Candidate>of());
        when(aiService.rerankAndExplain(userFeature, List.of(), 3)).thenReturn(List.of(generated));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllById(List.of(1L, 999L))).thenReturn(List.of(existingBenefit));

        List<Recommendations> result = recommendationService.recommend(7L, 3);

        org.mockito.Mockito.verify(recommendationRepository).saveAll(recommendationsCaptor.capture());
        assertThat(result).containsExactly(generated);
        assertThat(recommendationsCaptor.getValue())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getBenefits()).containsExactly(existingBenefit);
                    assertThat(saved.getCacheBatchId()).startsWith("rec-7-");
                    assertThat(saved.getAlgorithmVersion()).isEqualTo("personalized-es-quality-v1");
                    assertThat(saved.getActive()).isTrue();
                });
    }


    @Test
    void recommend_returnsOnlyLatestActiveBatchWhenCacheIsValid() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(10);
        Recommendation latest = Recommendation.builder()
                .cacheBatchId("batch-new")
                .active(true)
                .rank(1)
                .partnerName("최신파트너")
                .benefits(List.of())
                .build();
        ReflectionTestUtils.setField(latest, "createdDate", createdAt);
        Recommendation cached = Recommendation.builder()
                .cacheBatchId("batch-new")
                .active(true)
                .rank(1)
                .partnerName("최신파트너")
                .reason("최신 추천")
                .benefits(List.of())
                .build();

        when(recommendationRepository
                .findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(latest));
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(favoriteRepository.existsByUserIdAndCreatedDateAfter(7L, createdAt)).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).role(Role.USER).build()));
        when(recommendationRepository.findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(7L, "batch-new"))
                .thenReturn(List.of(cached));

        List<Recommendations> result = recommendationService.recommend(7L, 3);

        assertThat(result)
                .singleElement()
                .satisfies(recommendation -> assertThat(recommendation.getPartnerName()).isEqualTo("최신파트너"));
    }

    @Test
    void hasInvalidatingSignalAfter_returnsTrueWhenRecentBehaviorLogExists() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);
        Instant recentEventAt = latestRecommendationDate.plusMinutes(10)
                .atZone(ZoneId.systemDefault())
                .toInstant();

        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.of(recentEventAt));

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
    }

    @Test
    void hasInvalidatingSignalAfter_returnsTrueWhenNegativeFeedbackLogExists() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);
        Instant recentEventAt = latestRecommendationDate.plusMinutes(10)
                .atZone(ZoneId.systemDefault())
                .toInstant();

        when(logRepository.findLatestLoggingAtByEvents(eq(7L), argThat(events ->
                events.contains("favorite_remove")
                        && events.contains("dismiss")
                        && events.contains("skip")
                        && events.contains("negative_feedback")
                        && events.contains("feedback_negative")
                        && events.contains("not_interested"))))
                .thenReturn(Optional.of(recentEventAt));

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
    }

    @Test
    void hasInvalidatingSignalAfter_returnsTrueWhenUserProfileChangedAfterCachedRecommendation() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "lastModifiedDate", latestRecommendationDate.plusMinutes(10));

        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(favoriteRepository.existsByUserIdAndCreatedDateAfter(7L, latestRecommendationDate)).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
    }

    @Test
    void hasInvalidatingSignalAfter_returnsTrueWhenFavoriteChangedAfterCachedRecommendation() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);

        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(favoriteRepository.existsByUserIdAndCreatedDateAfter(7L, latestRecommendationDate)).thenReturn(true);

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
    }

}
