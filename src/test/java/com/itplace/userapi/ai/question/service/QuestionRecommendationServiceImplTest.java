package com.itplace.userapi.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.forbiddenword.service.ForbiddenWordService;
import com.itplace.userapi.ai.llm.dto.response.RecommendationResponse;
import com.itplace.userapi.ai.llm.service.OpenAIService;
import com.itplace.userapi.ai.question.exception.QuestionException;
import com.itplace.userapi.ai.question.guard.BenefitCandidateGuard;
import com.itplace.userapi.ai.question.intent.QueryIntentExtractor;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.map.dto.PartnerDto;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionRecommendationServiceImplTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private StoreService storeService;

    @Mock
    private OpenAIService openAIService;

    @Mock
    private ForbiddenWordService forbiddenWordService;

    @Mock
    private BenefitSearchService benefitSearchService;


    @Test
    void recommendByQuestion_usesUserMembershipFallbackWhenQuestionHasNoCarrier() {
        QuestionRecommendationServiceImpl service = service();
        String question = "근처에서 할인되는 카페 알려줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate candidate = Candidate.builder()
                .partnerName("할리스")
                .category("카페")
                .carrier("KT")
                .grade("KT_VVIP")
                .candidateSource("es_vector")
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("할리스")
                        .image("hollys.png")
                        .category("카페")
                        .build())
                .tierBenefit(List.of(TierBenefitDto.builder()
                        .context("KT VVIP 음료 할인")
                        .build()))
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("카페"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.KT),
                        org.mockito.ArgumentMatchers.eq(Grade.KT_VVIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class)))
                .thenReturn(List.of(candidate));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "할리스", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(
                question,
                37.5,
                127.0,
                null,
                null,
                Carrier.KT,
                Grade.KT_VVIP
        );

        assertThat(response.getReason()).contains("할리스").contains("KT VVIP 음료 할인");
        assertThat(response.getPartners())
                .singleElement()
                .satisfies(partner -> assertThat(partner.getPartnerName()).isEqualTo("할리스"));
    }

    @Test
    void recommendByQuestion_prefersQuestionCarrierOverUserMembershipFallback() {
        QuestionRecommendationServiceImpl service = service();
        String question = "SKT VIP 카페 혜택 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate candidate = Candidate.builder()
                .partnerName("투썸플레이스")
                .category("카페")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("투썸플레이스")
                        .image("twosome.png")
                        .category("카페")
                        .build())
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("SKT VIP"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class)))
                .thenReturn(List.of(candidate));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "투썸플레이스", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(
                question,
                37.5,
                127.0,
                null,
                null,
                Carrier.KT,
                Grade.KT_VVIP
        );

        assertThat(response.getPartners())
                .singleElement()
                .satisfies(partner -> assertThat(partner.getPartnerName()).isEqualTo("투썸플레이스"));
    }

    @Test
    void recommendByQuestion_usesBenefitRagOnlyWithoutQuestionMemoryOrCategoryFallback() {
        QuestionRecommendationServiceImpl service = service();
        String question = "영화 혜택 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate candidate = Candidate.builder()
                .partnerName("영화관")
                .category("영화")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("영화관")
                        .image("movie.png")
                        .category("영화")
                        .build())
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("영화"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class))).thenReturn(List.of(candidate));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "영화관", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(question, 37.5, 127.0, Carrier.SKT, Grade.SKT_VIP);

        assertThat(response.getReason()).contains("영화").contains("영화관");
        assertThat(response.getPartners())
                .singleElement()
                .satisfies(partner -> {
                    assertThat(partner.getPartnerName()).isEqualTo("영화관");
                    assertThat(partner.getImgUrl()).isEqualTo("movie.png");
                });
        verify(openAIService, never()).categorize(org.mockito.ArgumentMatchers.any());
        verify(storeService, never()).findNearbyByKeyword(
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(openAIService, never()).generateReasons(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void recommendByQuestion_rejectsHotWeatherFalseRouteAndDoesNotFallbackToCounselingCategory() {
        QuestionRecommendationServiceImpl service = service();
        String question = "날씨가 더운데 시원하게 갈만한 곳 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate falseRoute = Candidate.builder()
                .partnerName("상담센터")
                .category("상담")
                .description("결혼 상담 혜택")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .build();
        Candidate coolPlace = Candidate.builder()
                .partnerName("빙수카페")
                .category("카페")
                .description("시원한 디저트 할인")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("빙수카페")
                        .image("bingsu.png")
                        .category("카페")
                        .build())
                .tierBenefit(List.of(TierBenefitDto.builder()
                        .context("시원한 디저트 20% 할인")
                        .build()))
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("시원한 장소"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class))).thenReturn(List.of(falseRoute, coolPlace));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "빙수카페", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(question, 37.5, 127.0, Carrier.SKT, Grade.SKT_VIP);

        assertThat(response.getReason())
                .contains("더운 날 시원하게 쉬어가기 좋은 제휴처")
                .contains("• 빙수카페 — 시원한 디저트 20% 할인")
                .contains("\n\n")
                .doesNotContain("의도")
                .doesNotContain("후보");
        assertThat(response.getPartners())
                .singleElement()
                .satisfies(partner -> assertThat(partner.getPartnerName()).isEqualTo("빙수카페"));
        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "상담센터", 37.5, 127.0);
        verify(storeService, never()).findNearbyByKeyword(37.5, 127.0, null, "상담", 0, 0);
        verify(openAIService, never()).categorize(question);
    }

    @Test
    void recommendByQuestion_returnsSafeNoStoreWhenGuardRejectsEveryCandidate() {
        QuestionRecommendationServiceImpl service = service();
        String question = "날씨가 더운데 시원하게 갈만한 곳 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate falseRoute = Candidate.builder()
                .partnerName("상담센터")
                .category("상담")
                .description("결혼 상담 혜택")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("시원한 장소"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class))).thenReturn(List.of(falseRoute));

        assertThatThrownBy(() -> service.recommendByQuestion(question, 37.5, 127.0, Carrier.SKT, Grade.SKT_VIP))
                .isInstanceOf(QuestionException.class);

        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "상담센터", 37.5, 127.0);
        verify(storeService, never()).findNearbyByKeyword(
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(openAIService, never()).categorize(question);
    }

    @Test
    void recommendByQuestion_prioritizesDrinkIntentAndRejectsRestaurantFalseRoute() {
        QuestionRecommendationServiceImpl service = service();
        String question = "음료수 파는 곳 위주로 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate restaurant = Candidate.builder()
                .partnerName("삼산회관")
                .category("식당")
                .description("식사 메뉴와 음료 판매")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.95)
                .build();
        Candidate pizza = Candidate.builder()
                .partnerName("피자헛")
                .category("피자")
                .description("피자와 음료 세트")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.9)
                .build();
        Candidate kidsCafe = Candidate.builder()
                .partnerName("서울형 키즈카페")
                .category("키즈카페, 실내놀이터")
                .description("입장료 할인")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.99)
                .build();
        Candidate studyCafe = Candidate.builder()
                .partnerName("초심스터디카페")
                .category("독서실")
                .description("스터디카페 쿠폰")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.98)
                .build();
        Candidate cafe = Candidate.builder()
                .partnerName("카페베네")
                .category("카페")
                .description("커피와 음료 할인")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.7)
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("카페베네")
                        .image("drink.png")
                        .category("카페")
                        .build())
                .tierBenefit(List.of(TierBenefitDto.builder()
                        .context("음료 10% 할인")
                        .build()))
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("음료 중심"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class)))
                .thenReturn(List.of(kidsCafe, studyCafe, restaurant, pizza, cafe));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "카페베네", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(question, 37.5, 127.0, Carrier.SKT, Grade.SKT_VIP);

        assertThat(response.getReason())
                .contains("음료나 가벼운 디저트를 즐기기 좋은 제휴처")
                .contains("• 카페베네 — 음료 10% 할인")
                .contains("\n\n")
                .doesNotContain("의도")
                .doesNotContain("후보");
        assertThat(response.getPartners())
                .singleElement()
                .satisfies(partner -> assertThat(partner.getPartnerName()).isEqualTo("카페베네"));
        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "서울형 키즈카페", 37.5, 127.0);
        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "초심스터디카페", 37.5, 127.0);
        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "삼산회관", 37.5, 127.0);
        verify(storeService, never()).findNearbyByPartnerName(37.5, 127.0, "피자헛", 37.5, 127.0);
        verify(openAIService, never()).generateReasons(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void recommendByQuestion_explainsDateRecommendationWithBenefitGroundingNotInternalIntentText() {
        QuestionRecommendationServiceImpl service = service();
        String question = "데이트하기 좋은 제휴처 추천해줘";
        List<Float> embedding = List.of(0.1f, 0.2f);
        Candidate movie = Candidate.builder()
                .partnerName("CGV")
                .category("영화")
                .description("영화 예매 할인")
                .carrier("SKT")
                .grade("SKT_VIP")
                .candidateSource("es_vector")
                .semanticScore(0.8)
                .build();
        StoreDetailResponse store = StoreDetailResponse.builder()
                .partner(PartnerDto.builder()
                        .partnerName("CGV")
                        .image("movie.png")
                        .category("영화")
                        .build())
                .tierBenefit(List.of(TierBenefitDto.builder()
                        .context("영화 예매 30% 할인")
                        .build()))
                .build();

        when(forbiddenWordService.censor(question)).thenReturn(question);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.contains("데이트"))).thenReturn(embedding);
        when(benefitSearchService.queryHybrid(
                        org.mockito.ArgumentMatchers.eq(Carrier.SKT),
                        org.mockito.ArgumentMatchers.eq(Grade.SKT_VIP),
                        org.mockito.ArgumentMatchers.eq(embedding),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(30),
                        org.mockito.ArgumentMatchers.any(com.itplace.userapi.ai.rag.service.BenefitSearchCondition.class))).thenReturn(List.of(movie));
        when(storeService.findNearbyByPartnerName(37.5, 127.0, "CGV", 37.5, 127.0)).thenReturn(List.of(store));

        RecommendationResponse response = service.recommendByQuestion(question, 37.5, 127.0, Carrier.SKT, Grade.SKT_VIP);

        assertThat(response.getReason())
                .contains("데이트하기 좋은 제휴처")
                .contains("• CGV — 영화 예매 30% 할인")
                .contains("영화 예매 30% 할인")
                .contains("CGV")
                .contains("데이트")
                .contains("\n\n")
                .doesNotContain("의도")
                .doesNotContain("후보");
    }

    private QuestionRecommendationServiceImpl service() {
        return new QuestionRecommendationServiceImpl(
                embeddingService,
                storeService,
                openAIService,
                forbiddenWordService,
                benefitSearchService,
                new QueryIntentExtractor(),
                new BenefitCandidateGuard()
        );
    }
}
