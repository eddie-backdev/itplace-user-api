package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class RecommendationPersistenceServiceTest {

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private FavoriteRepository favoriteRepository;

    @InjectMocks
    private RecommendationPersistenceService persistenceService;

    @Test
    void replaceActiveBatch_filtersStaleBenefitIdsAndUsesOneBatchId() {
        User user = User.builder().id(7L).role(Role.USER).build();
        Benefit existingBenefit = Benefit.builder().benefitId(1L).benefitName("존재하는 혜택").build();
        Recommendations first = recommendation(1, List.of(1L, 999L));
        Recommendations second = recommendation(2, List.of(1L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllById(List.of(1L, 999L))).thenReturn(List.of(existingBenefit));

        persistenceService.replaceActiveBatch(
                7L,
                List.of(first, second),
                "personalized-es-quality-v1"
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Recommendation>> captor = ArgumentCaptor.forClass(List.class);
        verify(recommendationRepository).deactivateActiveByUserId(7L);
        verify(recommendationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(saved -> {
            assertThat(saved.getBenefits()).containsExactly(existingBenefit);
            assertThat(saved.getCacheBatchId()).startsWith("rec-7-");
            assertThat(saved.getAlgorithmVersion()).isEqualTo("personalized-es-quality-v1");
            assertThat(saved.getActive()).isTrue();
        });
        assertThat(captor.getValue().get(0).getCacheBatchId())
                .isEqualTo(captor.getValue().get(1).getCacheBatchId());
    }

    @Test
    void findLatestActiveBatch_mapsEntitiesInsidePersistenceBoundary() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(5);
        Benefit benefit = Benefit.builder().benefitId(1L).build();
        Recommendation latest = Recommendation.builder()
                .cacheBatchId("batch-1")
                .rank(1)
                .partnerName("파트너")
                .benefits(List.of(benefit))
                .active(true)
                .build();
        ReflectionTestUtils.setField(latest, "createdDate", createdAt);
        when(recommendationRepository
                .findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(7L, createdAt.minusDays(1)))
                .thenReturn(Optional.of(latest));
        when(recommendationRepository.findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(7L, "batch-1"))
                .thenReturn(List.of(latest));

        RecommendationPersistenceService.CachedBatch result = persistenceService
                .findLatestActiveBatch(7L, createdAt.minusDays(1))
                .orElseThrow();

        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.recommendations()).singleElement()
                .satisfies(recommendation -> assertThat(recommendation.getBenefitIds()).containsExactly(1L));
    }

    @Test
    void hasPersistentInvalidatingSignalAfter_shortCircuitsOnFavoriteChange() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
        when(favoriteRepository.existsByUserIdAndCreatedDateAfter(7L, createdAt)).thenReturn(true);

        assertThat(persistenceService.hasPersistentInvalidatingSignalAfter(7L, createdAt)).isTrue();
        verify(userRepository, never()).findById(7L);
    }

    @Test
    void replaceActiveBatch_skipsBenefitQueryWhenRecommendationsHaveNoBenefitIds() {
        User user = User.builder().id(7L).role(Role.USER).build();
        Recommendations recommendation = recommendation(1, List.of());
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        persistenceService.replaceActiveBatch(7L, List.of(recommendation), "personalized-es-quality-v1");

        verify(benefitRepository, never()).findAllById(anyList());
        verify(recommendationRepository).saveAll(anyList());
    }

    @Test
    void replaceActiveBatch_definesShortTransactionBoundary() throws Exception {
        Transactional transactional = RecommendationPersistenceService.class
                .getMethod("replaceActiveBatch", Long.class, List.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    private Recommendations recommendation(int rank, List<Long> benefitIds) {
        return Recommendations.builder()
                .rank(rank)
                .partnerName("파트너 " + rank)
                .reason("추천 이유")
                .benefitIds(benefitIds)
                .requestId("request-" + rank)
                .impressionId("impression-" + rank)
                .candidateSource("es_vector")
                .build();
    }
}
