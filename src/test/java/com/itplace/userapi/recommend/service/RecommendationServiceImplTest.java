package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
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
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        when(recommendationRepository.findLatestRecommendationDate(eq(7L), any(LocalDateTime.class)))
                .thenReturn(null);
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
                .satisfies(saved -> assertThat(saved.getBenefits()).containsExactly(existingBenefit));
    }
}
