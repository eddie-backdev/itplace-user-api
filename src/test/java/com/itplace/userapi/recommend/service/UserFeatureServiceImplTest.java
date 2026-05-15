package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserFeatureServiceImplTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private LogRepository logRepository;

    @Mock
    private FavoriteRepository favoriteRepository;

    @InjectMocks
    private UserFeatureServiceImpl userFeatureService;

    @Test
    void buildPartnerScoresWeightsFavoriteUsageDetailAndRecency() {
        Map<String, Double> scores = UserFeatureServiceImpl.buildPartnerScores(
                List.of("A", "B"),
                List.of("B"),
                List.of("C"),
                List.of("A"),
                List.of("D", "A")
        );

        assertThat(scores).containsEntry("D", 10.0);
        assertThat(scores.get("A")).isEqualTo(3.0 + 7.0 + 9.0);
        assertThat(scores.get("B")).isEqualTo(2.0 + 2.7);
        assertThat(scores.get("C")).isEqualTo(4.0);
        assertThat(scores.keySet()).containsExactly("A", "D", "B", "C");
    }

    @Test
    void buildPartnerScores_combinesEventWeightsFrequencyAndRecencyRank() {
        Map<String, Double> scores = UserFeatureServiceImpl.buildPartnerScores(
                List.of("클릭A", "복합파트너"),
                List.of("복합파트너", "검색B"),
                List.of("복합파트너"),
                List.of("즐겨찾기C", "즐겨찾기C"),
                List.of("사용D", "복합파트너")
        );

        assertThat(scores).containsEntry("복합파트너", 17.7);
        assertThat(scores).containsEntry("즐겨찾기C", 13.3);
        assertThat(scores).containsEntry("사용D", 10.0);
        assertThat(scores).containsEntry("클릭A", 3.0);
        assertThat(scores).containsEntry("검색B", 1.8);
        assertThat(scores.keySet()).containsSubsequence("복합파트너", "즐겨찾기C", "사용D", "클릭A", "검색B");
    }

    @Test
    void loadUserFeature_forColdStartUserIncludesFavoritesAndBehaviorSignalsWithoutHistory() {
        Long userId = 12L;
        User user = User.builder()
                .id(userId)
                .role(Role.USER)
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .build();
        Benefit favoriteBenefit = Benefit.builder()
                .benefitId(101L)
                .partner(Partner.builder().partnerName("즐겨찾기파트너").build())
                .build();
        Favorite favorite = Favorite.builder()
                .user(user)
                .benefit(favoriteBenefit)
                .build();

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "click", 5)).thenReturn(List.of("클릭파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "search", 5)).thenReturn(List.of("검색파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "detail", 5)).thenReturn(List.of("상세파트너"));
        when(favoriteRepository.findByUserIdWithBenefitAndPartner(userId)).thenReturn(List.of(favorite));

        UserFeature feature = userFeatureService.loadUserFeature(userId);

        assertThat(feature.getFeatureSnapshotVersion()).isEqualTo(UserFeatureServiceImpl.FEATURE_SNAPSHOT_VERSION);
        assertThat(feature.getCarrier()).isEqualTo(Carrier.LGU);
        assertThat(feature.getFavoritePartners()).containsExactly("즐겨찾기파트너");
        assertThat(feature.getClickPartners()).containsExactly("클릭파트너");
        assertThat(feature.getSearchPartners()).containsExactly("검색파트너");
        assertThat(feature.getDetailPartners()).containsExactly("상세파트너");
        assertThat(feature.getPartnerAffinityScores())
                .containsEntry("즐겨찾기파트너", 7.0)
                .containsEntry("상세파트너", 4.0)
                .containsEntry("클릭파트너", 3.0)
                .containsEntry("검색파트너", 2.0);
        assertThat(feature.getRecentPartnerNames()).containsExactly(
                "즐겨찾기파트너", "상세파트너", "클릭파트너", "검색파트너");
        assertThat(feature.hasSignalForPartner("즐겨찾기파트너")).isTrue();
        assertThat(feature.getNegativePartnerScores()).isEmpty();
    }

    @Test
    void loadUserFeature_usesProfileGradeWithoutLegacyMembershipHistory() {
        Long userId = 21L;
        User user = User.builder()
                .id(userId)
                .role(Role.USER)
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VVIP)
                .build();

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "click", 5)).thenReturn(List.of());
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "search", 5)).thenReturn(List.of("검색파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "detail", 5)).thenReturn(List.of());
        when(favoriteRepository.findByUserIdWithBenefitAndPartner(userId)).thenReturn(List.of());

        UserFeature feature = userFeatureService.loadUserFeature(userId);

        assertThat(feature.getCarrier()).isEqualTo(Carrier.LGU);
        assertThat(feature.getGrade()).isEqualTo(Grade.VVIP);
        assertThat(feature.getTopCategories()).isEmpty();
        assertThat(feature.getBenefitUsageCounts()).isEmpty();
        assertThat(feature.getRecentPartnerNames()).containsExactly("검색파트너");
        assertThat(feature.getPartnerAffinityScores()).containsEntry("검색파트너", 2.0);
        assertThat(feature.getCategoryAffinityScores()).isEmpty();
        assertThat(feature.getEmbeddingText())
                .contains("통신사/등급 조건: LGU / VVIP")
                .contains("검색 [검색파트너]")
                .contains("종합 행동 상위 [검색파트너]");
    }

    @DisplayName("favorite_remove, dismiss, skip, negative, and no-click impressions contribute negative scores and tombstones")
    @Test
    void negativeAndFatigueSignalsSuppressLowResponseBenefits() {
        Long userId = 31L;
        User user = User.builder()
                .id(userId)
                .role(Role.USER)
                .carrier(Carrier.SKT)
                .membershipGradeCode(Grade.SKT_VIP)
                .build();

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "click", 5)).thenReturn(List.of("반응파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "search", 5)).thenReturn(List.of());
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "detail", 5)).thenReturn(List.of());
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "impression", 10))
                .thenReturn(List.of("피로파트너", "반응파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "favorite_remove", 10))
                .thenReturn(List.of("삭제파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "dismiss", 10))
                .thenReturn(List.of("숨김파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "skip", 10))
                .thenReturn(List.of("건너뜀파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "negative", 10))
                .thenReturn(List.of("부정파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "negative_feedback", 10))
                .thenReturn(List.of("피드백파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "feedback_negative", 10))
                .thenReturn(List.of("명시부정파트너"));
        when(logRepository.aggregateTopPartnerNamesByEvent(userId, "not_interested", 10))
                .thenReturn(List.of());
        when(logRepository.findLatestParamByEvents(eq(userId), any()))
                .thenReturn(Optional.of("lat=37.5&lng=127.0&city=서울"));
        when(favoriteRepository.findByUserIdWithBenefitAndPartner(userId)).thenReturn(List.of());

        UserFeature feature = userFeatureService.loadUserFeature(userId);

        assertThat(feature.getNegativePartnerScores())
                .containsEntry("부정파트너", 10.0)
                .containsEntry("숨김파트너", 8.0)
                .containsEntry("건너뜀파트너", 6.0)
                .containsEntry("삭제파트너", 4.0)
                .containsEntry("피로파트너", 1.5)
                .doesNotContainKey("반응파트너");
        assertThat(feature.getTombstonedPartners())
                .contains("숨김파트너", "건너뜀파트너", "부정파트너", "피드백파트너", "명시부정파트너");
        assertThat(feature.getLocationContext()).startsWith("KNOWN(");
        assertThat(feature.getEmbeddingText())
                .contains("통신사/등급 조건: SKT / SKT_VIP")
                .contains("위치 컨텍스트: KNOWN(")
                .contains("부정/피로 신호 제휴사")
                .contains("negativeScores");
        assertThat(feature.getLLMContext())
                .contains("부정/피로 신호 제휴사")
                .contains("위치 컨텍스트는 KNOWN(");
    }

}
