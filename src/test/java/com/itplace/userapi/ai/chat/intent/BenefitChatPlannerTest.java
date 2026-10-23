package com.itplace.userapi.ai.chat.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatRequest.ChatMessage;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.reference.TestReferenceDataService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

class BenefitChatPlannerTest {
    private final TestReferenceDataService referenceData = new TestReferenceDataService();
    private final BenefitChatPlanner planner = new BenefitChatPlanner(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);

    @BeforeEach void wireCatalog() throws Exception {
        ReflectionTestUtils.setField(planner, "referenceDataService", referenceData);
        PartnerCatalogResolver catalog = new PartnerCatalogResolver(null);
        catalog.setNamesForTest(frozenCatalog());
        ReflectionTestUtils.setField(planner, "partnerCatalogResolver", catalog);
    }

    private List<PartnerCatalogResolver.Name> frozenCatalog() throws Exception {
        var root = new ObjectMapper().readTree(getClass().getResourceAsStream("/com/itplace/userapi/ai/chat/intent/partner-catalog-2026-10-23.json"));
        Map<String, List<String>> candidates = new TreeMap<>();
        for (var partner : root) {
            String canonical = partner.path("partnerName").asText();
            addCatalogVariant(candidates, canonical, canonical);
            for (var alias : partner.path("aliases")) {
                addCatalogVariant(candidates, alias.path("aliasName").asText(), canonical);
                addCatalogVariant(candidates, alias.path("normalizedAlias").asText(), canonical);
            }
        }
        return PartnerCatalogResolver.resolveCandidates(candidates);
    }

    private void addCatalogVariant(Map<String, List<String>> candidates, String variant, String canonical) {
        String normalized = PartnerCatalogResolver.normalize(variant);
        if (!normalized.isBlank()) candidates.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(canonical);
    }

    private BenefitChatPlanner enabledPlanner(OpenAiChatModel model) {
        BenefitChatPlanner instance = new BenefitChatPlanner(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        ReflectionTestUtils.setField(instance, "referenceDataService", referenceData);
        ReflectionTestUtils.setField(instance, "partnerCatalogResolver", ReflectionTestUtils.getField(planner, "partnerCatalogResolver"));
        return instance;
    }

    @Test
    void shortMovieQuestionIsSearchableWithoutMembershipProfile() {
        BenefitChatPlan plan = planner.plan(request("영화 할인"), null, null);

        assertThat(plan.mode()).isEqualTo("SEARCH");
        assertThat(plan.categories()).contains("영화");
        assertThat(plan.query()).contains("영화");
        assertThat(plan.carriers()).isEmpty();
        assertThat(plan.grade()).isNull();
    }

    @Test
    void exactCarrierAndComparisonRepliesKeepSubjectAndComparisonOverridesProfile() {
        BenefitChatPlan kt = planner.plan(request("KT", new ChatMessage("user", "영화 할인")), Carrier.SKT, Grade.SKT_VIP);
        BenefitChatPlan gradeBeforeCarrier = planner.plan(request("KT", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "VIP야")), Carrier.SKT, Grade.SKT_VIP);
        BenefitChatPlan all = planner.plan(request("전체 비교", new ChatMessage("user", "영화 할인")), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan continued = planner.plan(request("카페 혜택", new ChatMessage("user", "통신사 전체 비교")), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan selectedAfterCompare = planner.plan(request("SKT", new ChatMessage("user", "통신사 전체 비교")), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan pairFollowUp = planner.plan(request("CGV 말고", new ChatMessage("user", "KT와 SKT 통신사 비교")), Carrier.LGU, Grade.VIP);
        BenefitChatPlan explicitAllAfterPair = planner.plan(request("전체 비교", new ChatMessage("user", "KT와 SKT 통신사 비교")), Carrier.LGU, Grade.VIP);

        assertThat(kt.carriers()).containsExactly(Carrier.KT);
        assertThat(kt.query()).contains("영화");
        assertThat(kt.grade()).isNull();
        assertThat(gradeBeforeCarrier.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(all.compareCarriers()).isTrue();
        assertThat(all.carriers()).containsExactly(Carrier.SKT, Carrier.KT, Carrier.LGU);
        assertThat(all.grade()).isNull();
        assertThat(continued.compareCarriers()).isTrue();
        assertThat(continued.categories()).containsExactly("카페/디저트");
        assertThat(selectedAfterCompare.compareCarriers()).isFalse();
        assertThat(selectedAfterCompare.carriers()).containsExactly(Carrier.SKT);
        assertThat(pairFollowUp.compareCarriers()).isTrue();
        assertThat(pairFollowUp.carriers()).containsExactlyInAnyOrder(Carrier.KT, Carrier.SKT);
        assertThat(explicitAllAfterPair.carriers()).containsExactly(Carrier.SKT, Carrier.KT, Carrier.LGU);
    }

    @Test
    void unknownGradeIsOnlyMarkedFromUserAnswerAndClearsOnKnownGradeOrCarrierSwitch() {
        BenefitChatPlan unknown = planner.plan(request("KT 등급은 모르겠어", new ChatMessage("user", "영화 할인")), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan unknownFollowUp = planner.plan(request("등급은 모르겠어", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야")), null, null);
        BenefitChatPlan sameCarrierTopic = planner.plan(request("KT 영화", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야"), new ChatMessage("user", "등급은 모르겠어")), null, null);
        BenefitChatPlan switched = planner.plan(request("SKT", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야"), new ChatMessage("user", "등급은 모르겠어")), null, null);
        BenefitChatPlan known = planner.plan(request("VIP야", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야"), new ChatMessage("user", "등급은 모르겠어")), null, null);

        assertThat(unknown.gradeUnspecified()).isTrue();
        assertThat(unknown.grade()).isNull();
        assertThat(unknownFollowUp.gradeUnspecified()).isTrue();
        assertThat(unknownFollowUp.followUp()).isTrue();
        assertThat(unknownFollowUp.query()).contains("영화");
        assertThat(sameCarrierTopic.gradeUnspecified()).isTrue();
        assertThat(switched.carriers()).containsExactly(Carrier.SKT);
        assertThat(switched.gradeUnspecified()).isFalse();
        assertThat(known.gradeUnspecified()).isFalse();
        assertThat(known.grade()).isEqualTo(Grade.KT_VIP);
    }

    @Test
    void explicitUnknownGradeOverridesProfileAndPersistsUntilKnownGradeArrives() {
        BenefitChatPlan skipped = planner.plan(request("KT 등급은 모르겠어"), Carrier.KT, Grade.KT_VIP);
        List<ChatMessage> turns = List.of(new ChatMessage("user", "영화 할인"), new ChatMessage("user", "나 KT야"),
                new ChatMessage("user", "등급은 모르겠어"));
        BenefitChatPlan stillSkipped = planner.plan(new BenefitChatRequest("영화도 알려줘", turns, List.of(), null, null, null, null), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan knownLater = planner.plan(new BenefitChatRequest("VIP야", turns, List.of(), null, null, null, null), Carrier.KT, Grade.KT_VIP);

        assertThat(skipped.gradeUnspecified()).isTrue();
        assertThat(skipped.grade()).isNull();
        assertThat(stillSkipped.gradeUnspecified()).isTrue();
        assertThat(stillSkipped.grade()).isNull();
        assertThat(knownLater.gradeUnspecified()).isFalse();
        assertThat(knownLater.grade()).isEqualTo(Grade.KT_VIP);
    }

    @Test
    void carrierAndGradeRepliesPreservePriorDetailIntentButNewMovieRequestSearches() {
        List<ChatMessage> detail = List.of(new ChatMessage("user", "CGV 이용 조건 알려줘"));
        BenefitChatPlan carrierReply = planner.plan(new BenefitChatRequest("KT", detail, List.of(), null, null, null, null), null, null);
        BenefitChatPlan gradeReply = planner.plan(new BenefitChatRequest("KT VIP 등급이야",
                append(detail, "KT"), List.of(), null, null, null, null), null, null);
        BenefitChatPlan unsupportedGrade = planner.plan(new BenefitChatRequest("KT 다이아몬드",
                detail, List.of(), null, null, null, null), null, null);
        BenefitChatPlan unrelated = planner.plan(new BenefitChatRequest("KT 안녕",
                detail, List.of(), null, null, null, null), null, null);
        BenefitChatPlan movieRecommendation = planner.plan(new BenefitChatRequest("영화 혜택 추천",
                detail, List.of(), null, null, null, null), null, null);

        assertThat(carrierReply.mode()).isEqualTo("DETAIL");
        assertThat(carrierReply.query()).contains("CGV");
        assertThat(gradeReply.mode()).isEqualTo("DETAIL");
        assertThat(gradeReply.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(unsupportedGrade.mode()).isEqualTo("CLARIFY");
        assertThat(unrelated.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(movieRecommendation.mode()).isEqualTo("SEARCH");
    }

    @Test
    void specificDiscountAmountFollowUpUsesDetailModeAndPriorCandidates() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"이마트24 할인 금액\",\"categories\":[\"쇼핑\"],\"partners\":[\"이마트\"],\"excludedPartners\":[],\"carriers\":[\"LGU\"],\"grade\":\"BASIC\",\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);
        BenefitChatRequest request = new BenefitChatRequest("LG유플러스 우수 등급인데 이마트24 할인은 얼마야?",
                List.of(new ChatMessage("user", "편의점 혜택")), List.of("benefit:11:policy:21:tier:31"),
                null, null, null, null);

        BenefitChatPlan plan = enabledPlanner.plan(request, null, null);
        BenefitChatPlan standalone = planner.plan(request("LG유플러스 우수 등급인데 이마트24 할인은 얼마야?"), null, null);

        assertThat(plan.mode()).isEqualTo("DETAIL");
        assertThat(plan.carriers()).containsExactly(Carrier.LGU);
        assertThat(plan.grade()).isNotNull();
        assertThat(plan.categories()).containsExactly("편의점");
        assertThat(plan.partners()).containsExactly("이마트24");
        assertThat(plan.followUp()).isTrue();
        assertThat(standalone.mode()).isEqualTo("DETAIL");
        assertThat(standalone.categories()).containsExactly("편의점");
        assertThat(standalone.partners()).containsExactly("이마트24");
    }

    @Test
    void explicitLguDiamondQuestionSelectsBenefitSourceEligibility() {
        BenefitChatPlan plan = planner.plan(request("LG유플러스 다이아몬드 영화 혜택"), null, null);

        assertThat(plan.carriers()).containsExactly(Carrier.LGU);
        assertThat(plan.grade()).isEqualTo(Grade.DIAMOND);
    }

    @Test
    void convenienceStoreIsItsOwnTopicRatherThanShopping() {
        BenefitChatPlan plan = planner.plan(request("편의점할인"), null, null);

        assertThat(plan.mode()).isEqualTo("SEARCH");
        assertThat(plan.categories()).containsExactly("편의점");
        assertThat(plan.query()).contains("편의점").doesNotContain("쇼핑");
    }

    @Test
    void explicitBusinessCategoriesMapToSearchableTypesWithoutLumpingTravelSectors() {
        assertCategory("호텔 할인", "호텔");
        assertCategory("숙박 혜택", "호텔");
        assertCategory("워터파크 할인", "워터파크");
        assertCategory("놀이공원 혜택", "놀이공원");
        assertCategory("테마파크 할인", "놀이공원");
        assertCategory("자동차 정비 혜택", "정비");
        assertCategory("세차 할인", "정비");
        assertCategory("엔진오일 혜택", "정비");
        assertCategory("심리상담 혜택", "상담");
        assertCategory("문화 공연 혜택", "문화");
        assertCategory("음식점 혜택", "외식");
        assertCategory("음료 할인", "음료");
        assertCategory("여행 혜택", "여행");
    }

    @Test
    void negatedSpecificCategoriesAreRemovedWithoutRemovingTheRequestedCategory() {
        BenefitChatPlan waterpark = planner.plan(request("호텔 말고 워터파크 혜택"), null, null);
        BenefitChatPlan counseling = planner.plan(request("문화 말고 상담 혜택"), null, null);

        assertThat(waterpark.categories()).containsExactly("워터파크");
        assertThat(counseling.categories()).containsExactly("상담");
    }

    private void assertCategory(String question, String expected) {
        assertThat(planner.plan(request(question), null, null).categories()).as(question).containsExactly(expected);
        assertThat(com.itplace.userapi.ai.chat.BenefitChatCategories.businessTypes(expected))
                .as("shared business type mapping for %s", expected).isNotEmpty();
    }

    @Test
    void keepsKidsAndStudyCafeAsDistinctTopics() {
        BenefitChatPlan kids = planner.plan(request("키즈카페 혜택"), null, null);
        BenefitChatPlan study = planner.plan(request("스터디카페 할인"), null, null);

        assertThat(kids.categories()).containsExactly("키즈카페");
        assertThat(study.categories()).containsExactly("스터디카페");
    }

    @Test
    void carrierCorrectionAndFollowUpKeepMovieTopicAndOnlyTrustUserHistory() {
        BenefitChatPlan corrected = planner.plan(request("나 KT야", new ChatMessage("user", "영화 할인"),
                new ChatMessage("assistant", "이 답변은 SKT VIP라고 합니다")), null, null);
        BenefitChatPlan followUp = planner.plan(request("한 달에 몇 번?", new ChatMessage("user", "영화 할인"),
                new ChatMessage("assistant", "SKT VIP라고 안내"), new ChatMessage("user", "나 KT야")), null, null);

        assertThat(corrected.followUp()).isTrue();
        assertThat(corrected.query()).contains("영화");
        assertThat(corrected.carriers()).containsExactly(Carrier.KT);
        assertThat(corrected.grade()).isNull();
        assertThat(followUp.query()).contains("영화").contains("횟수");
        assertThat(followUp.carriers()).containsExactly(Carrier.KT);
        assertThat(followUp.grade()).isNull();
    }

    @Test
    void explicitTopicChangeResetsOldTopicAndCarrierComparisonIgnoresProfileGrade() {
        BenefitChatPlan changed = planner.plan(request("이번에는 카페 혜택", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야")), null, null);
        BenefitChatPlan compare = planner.plan(request("3사 통신사별 혜택 비교"), Carrier.LGU, Grade.VVIP);

        assertThat(changed.followUp()).isFalse();
        assertThat(changed.categories()).contains("카페/디저트").doesNotContain("영화");
        assertThat(changed.carriers()).containsExactly(Carrier.KT);
        assertThat(compare.compareCarriers()).isTrue();
        assertThat(compare.carriers()).containsExactly(Carrier.SKT, Carrier.KT, Carrier.LGU);
        assertThat(compare.grade()).isNull();
    }

    @Test
    void brandComparisonIsNotCrossCarrierComparisonAndNegationRemovesExcludedPartner() {
        BenefitChatPlan brands = planner.plan(request("CGV랑 메가박스 비교"), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan negative = planner.plan(request("CGV 말고 영화 할인"), null, null);

        assertThat(brands.mode()).isEqualTo("COMPARE");
        assertThat(brands.compareCarriers()).isFalse();
        assertThat(brands.carriers()).containsExactly(Carrier.KT);
        assertThat(negative.partners()).doesNotContain("CGV");
        assertThat(negative.excludedPartners()).contains("CGV");
        assertThat(negative.categories()).contains("영화");
    }

    @Test
    void consecutiveNegatedCatalogBrandsStayExcludedWithoutRemovingTheNextPositiveBrand() {
        BenefitChatPlan convenience = planner.plan(request("GS25랑 CU 말고 편의점 혜택"), null, null);
        BenefitChatPlan movies = planner.plan(request("CGV, 롯데시네마 빼고 영화 혜택"), null, null);
        BenefitChatPlan positiveAfterNegation = planner.plan(request("CGV 말고 롯데시네마 혜택"), null, null);

        assertThat(convenience.excludedPartners()).containsExactlyInAnyOrder("GS25", "CU");
        assertThat(movies.excludedPartners()).containsExactlyInAnyOrder("CGV", "롯데시네마");
        assertThat(positiveAfterNegation.excludedPartners()).containsExactly("CGV");
        assertThat(positiveAfterNegation.partners()).containsExactly("롯데시네마");
    }

    @Test
    void directKnownSearchAndDetailIntentsSkipTheSlowProvider() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        BenefitChatPlanner enabled = enabledPlanner(model);

        enabled.plan(request("편의점"), null, null);
        enabled.plan(request("영화 할인"), null, null);
        enabled.plan(request("파고다토쿨 KT 혜택"), null, null);
        enabled.plan(request("CGV 이용 조건"), null, null);

        org.mockito.Mockito.verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void userNamedCatalogPartnerStaysTheOnlyPartnerWhenModelBroadensTheSearch() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"query\":\"SKT 멤버십 혜택\",\"categories\":[\"외식\"],\"partners\":[\"동구밭\",\"타요\"],\"excludedPartners\":[],\"carriers\":[\"SKT\"],\"grade\":null,\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));

        BenefitChatPlan plan = enabledPlanner(model).plan(
                request("클럽디 오아시스 혜택 중 SK텔레콤 회원이 쓸 수 있는 걸 알려줘."), null, null);

        assertThat(plan.partners()).containsExactly("클럽디 오아시스");
        assertThat(plan.carriers()).containsExactly(Carrier.SKT);
        assertThat(plan.categories()).doesNotContain("외식");
    }

    @Test
    void vipCockIsNotMisreadAsAnExplicitVipGrade() {
        BenefitChatPlan plan = planner.plan(new BenefitChatRequest("스타벅스 VIP콕의 핵심 혜택을 한 문장으로 알려줘.",
                List.of(), List.of(), null, null, Carrier.LGU, Grade.VVIP), null, null);

        assertThat(plan.partners()).containsExactly("스타벅스");
        assertThat(plan.grade()).isEqualTo(Grade.VVIP);
    }

    @Test
    void issuanceAndCanIUseQuestionsDoNotSetAChannelUnlessUserRequestsOne() {
        BenefitChatPlan issue = planner.plan(request("보리보리 쿠폰은 어느 앱에서 발급해?"), null, null);
        BenefitChatPlan use = planner.plan(request("굽네 앱쿠폰을 매장에서 쓸 수 있어?"), null, null);
        BenefitChatPlan pricing = planner.plan(request("엘리하이/엠베스트 제휴 할인에서 매장 가격에 어떤 수치가 적용돼?"), null, null);
        BenefitChatPlan onlineOnly = planner.plan(request("영화 온라인 혜택만 찾아줘"), null, null);
        BenefitChatPlan offlineOnly = planner.plan(request("영화 오프라인 혜택만 찾아줘"), null, null);

        assertThat(issue.mode()).isEqualTo("DETAIL");
        assertThat(issue.channel()).isEqualTo("ANY");
        assertThat(use.mode()).isEqualTo("DETAIL");
        assertThat(use.channel()).isEqualTo("ANY");
        assertThat(pricing.channel()).isEqualTo("ANY");
        assertThat(onlineOnly.channel()).isEqualTo("ONLINE");
        assertThat(offlineOnly.channel()).isEqualTo("OFFLINE");
    }

    @Test
    void dessertOnlySearchesAvoidCafeAndNegatedDessertDoesNotBroadenCoffee() {
        BenefitChatPlan dessert = planner.plan(request("디저트 할인 혜택"), null, null);
        BenefitChatPlan shavedIce = planner.plan(request("빙수 할인"), null, null);
        BenefitChatPlan coffee = planner.plan(request("디저트 말고 커피 혜택"), null, null);

        assertThat(dessert.categories()).containsExactly("디저트");
        assertThat(shavedIce.categories()).containsExactly("디저트");
        assertThat(com.itplace.userapi.ai.chat.BenefitChatCategories.businessTypes(dessert.categories()))
                .containsExactly("DESSERT");
        assertThat(coffee.categories()).containsExactly("카페");
        assertThat(com.itplace.userapi.ai.chat.BenefitChatCategories.businessTypes(coffee.categories()))
                .containsExactly("BEVERAGE_CAFE");
    }

    @Test
    void alternativeCafeOrDessertTopicKeepsTheCombinedCategoryScope() {
        BenefitChatPlan plan = planner.plan(request("카페나 디저트 혜택"), Carrier.KT, Grade.KT_GENERAL);

        assertThat(plan.categories()).containsExactly("카페/디저트");
        assertThat(plan.partners()).isEmpty();
        assertThat(com.itplace.userapi.ai.chat.BenefitChatCategories.businessTypes(plan.categories()))
                .containsExactly("BEVERAGE_CAFE", "DESSERT");
    }

    @Test
    void commonParisBaguetteSpellingVariantResolvesToCatalogCanonical() {
        BenefitChatPlan plan = planner.plan(request("파리바게트 멤버십 할인"), Carrier.KT, Grade.KT_GOLD);

        assertThat(plan.partners()).containsExactly("파리바게뜨");
        assertThat(plan.categories()).doesNotContain("외식");
    }

    @Test
    void catalogBrandsAndAliasesUseLongestMatchAndUnknownNamesStayInQuery() {
        BenefitChatPlan longBrand = planner.plan(request("GS25 편의점 택배 혜택"), null, null);
        BenefitChatPlan alias = planner.plan(request("파고다 혜택"), null, null);
        BenefitChatPlan unknown = planner.plan(request("모르는브랜드 혜택"), null, null);
        BenefitChatPlan excluded = planner.plan(request("파고다어학원 말고 학원 혜택"), null, null);
        BenefitChatPlan negativeLongBrand = planner.plan(request("GS25 편의점 택배 말고 영화 혜택"), null, null);
        BenefitChatPlan typo = planner.plan(request("파고다톸 혜택"), null, null);

        assertThat(longBrand.partners()).containsExactly("GS25편의점택배").doesNotContain("GS25");
        assertThat(alias.partners()).containsExactly("파고다");
        assertThat(unknown.partners()).containsExactly("모르는브랜드");
        assertThat(unknown.query()).contains("모르는브랜드");
        assertThat(excluded.partners()).isEmpty();
        assertThat(excluded.excludedPartners()).containsExactly("파고다어학원");
        assertThat(excluded.categories()).containsExactly("교육");
        assertThat(negativeLongBrand.excludedPartners()).containsExactly("GS25편의점택배");
        assertThat(negativeLongBrand.categories()).containsExactly("영화");
        assertThat(typo.partners()).isEmpty();
        assertThat(typo.query()).contains("파고다톸");
    }

    @Test
    void fictionalCatalogNamesStayExplicitPartnerFiltersWithoutBecomingKnownCategories() {
        List<String> questions = List.of(
                "코덱스달빛편의점에서 KT 멤버십 혜택 받을 수 있어? 등록된 할인과 등급을 찾아줘.",
                "달빛김밥연구소 제휴 정보가 있어? SK텔레콤 기준으로 혜택을 알려줘.",
                "은하수문구마켓 할인 혜택의 대상과 사용 조건을 알려줘.",
                "KT 고객이 파란숲키즈파크에서 쓸 수 있는 제휴 할인이나 적립이 있는지 확인해줘.",
                "모래시계세탁소 멤버십 혜택이 등록돼 있는지 찾아보고 내용도 요약해줘.",
                "구름정원베이커리에서 받을 수 있는 통신사 멤버십 혜택과 등급 조건은 뭐야?",
                "별빛자전거샵 제휴처에서 할인받을 수 있다고 들었는데 어떤 혜택인지 알려줘.",
                "SK텔레콤 멤버십으로 오후네시꽃집을 이용할 때 할인율과 한도가 어떻게 돼?",
                "청록고래서점 제휴 혜택의 이용 횟수 제한과 적용 조건을 확인해줘.",
                "작은행성약국에 멤버십 할인 정보가 있으면 원문 기준으로 알려줘.");
        List<String> names = List.of("코덱스달빛편의점", "달빛김밥연구소", "은하수문구마켓", "파란숲키즈파크",
                "모래시계세탁소", "구름정원베이커리", "별빛자전거샵", "오후네시꽃집", "청록고래서점", "작은행성약국");

        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i);
            BenefitChatPlan plan = planner.plan(request(question), null, null);
            assertThat(plan.partners()).as(question).containsExactly(names.get(i));
            assertThat(plan.categories()).as(question).doesNotContain("편의점", "키즈카페", "교육", "카페/디저트");
            assertThat(plan.query()).as(question).contains(question.substring(0, question.indexOf(' ')));
        }
    }

    @Test
    void explicitlyNamedUnknownPartnerRemainsScopedWhenProviderFailsOrReturnsInvalidJson() {
        OpenAiChatModel unavailable = mock(OpenAiChatModel.class);
        when(unavailable.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("provider offline")));
        OpenAiChatModel malformed = mock(OpenAiChatModel.class);
        when(malformed.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("not json"))))));

        for (BenefitChatPlanner enabled : List.of(enabledPlanner(unavailable), enabledPlanner(malformed))) {
            BenefitChatPlan plan = enabled.plan(request("코덱스달빛편의점에서 KT 멤버십 혜택 받을 수 있어?"), null, null);
            assertThat(plan.partners()).containsExactly("코덱스달빛편의점");
            assertThat(plan.query()).contains("코덱스달빛편의점");
            assertThat(plan.categories()).doesNotContain("편의점");
        }
    }

    @Test
    void explicitlyNamedUnknownPartnerStaysScopedWhenProviderProposesUnrelatedCatalogNames() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"query\":\"KT 멤버십 혜택\",\"categories\":[\"편의점\"],\"partners\":[\"동구밭\",\"타요키즈카페\"],\"excludedPartners\":[],\"carriers\":[\"KT\"],\"grade\":null,\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));

        BenefitChatPlan plan = enabledPlanner(model).plan(request(
                "코덱스달빛편의점에서 KT 멤버십 혜택 받을 수 있어? 등록된 할인과 등급을 찾아줘."), null, null);

        assertThat(plan.partners()).containsExactly("코덱스달빛편의점");
        assertThat(plan.categories()).doesNotContain("편의점");
    }

    @Test
    void embeddedCatalogNamesStayPartOfAnUnverifiedBrandToken() {
        List<String> questions = List.of(
                "가상GS25초록멤버십 혜택", "알파스타벅스테스트제휴 할인", "메가박스가 아닌 메가박스플러스테스트 혜택",
                "미등록CGV패밀리센터 제휴 할인", "상상CU클럽 할인 혜택", "테스트롯데시네마플러스 혜택",
                "가상파리바게뜨온리 혜택", "없는도미노피자플러스 멤버십 할인");
        List<String> expected = List.of("가상GS25초록멤버십", "알파스타벅스테스트제휴", "메가박스플러스테스트",
                "미등록CGV패밀리센터", "상상CU클럽", "테스트롯데시네마플러스", "가상파리바게뜨온리", "없는도미노피자플러스");

        for (int i = 0; i < questions.size(); i++) {
            BenefitChatPlan plan = planner.plan(request(questions.get(i)), null, null);
            assertThat(plan.partners()).as(questions.get(i)).containsExactly(expected.get(i));
            assertThat(plan.query()).as(questions.get(i)).contains(expected.get(i));
        }
    }

    @Test
    void explicitPositiveTopicsReplaceNegatedTopicsAcrossNaturalKoreanForms() {
        BenefitChatPlan food = planner.plan(request("영화 혜택이 아니라 식당 할인 찾아줘"), null, null);
        BenefitChatPlan movieAlternatives = planner.plan(request("메가박스는 제외, CGV나 롯데시네마 혜택"), null, null);

        assertThat(food.categories()).containsExactly("외식").doesNotContain("영화");
        assertThat(movieAlternatives.excludedPartners()).containsExactly("메가박스");
        assertThat(movieAlternatives.partners()).containsExactlyInAnyOrder("CGV", "롯데시네마");
    }

    @Test
    void unknownPartnerFallbackDoesNotTurnOrdinaryKoreanNounsIntoPartnerFilters() {
        BenefitChatPlan topicChange = planner.plan(request("이번에는 카페 혜택"), null, null);
        BenefitChatPlan generic = planner.plan(request("프랜차이즈 할인"), null, null);
        BenefitChatPlan couponQuestion = planner.plan(request("보리보리 쿠폰은 어느 앱에서 발급해?"), null, null);

        assertThat(topicChange.partners()).isEmpty();
        assertThat(generic.partners()).isEmpty();
        assertThat(couponQuestion.partners()).containsExactly("보리보리");
    }

    @Test
    void frozenKnownQuestionSetsDoNotAcquireInventedPartnerFilters() throws Exception {
        var cases = new ObjectMapper().readTree(getClass().getResourceAsStream(
                "/com/itplace/userapi/ai/chat/intent/planner-fallback-regression-2026-10-23.json"));

        assertThat(cases.size()).isEqualTo(191);
        PartnerCatalogResolver catalog = (PartnerCatalogResolver) ReflectionTestUtils.getField(planner, "partnerCatalogResolver");
        for (var testCase : cases) {
            if (testCase.path("unknownPartnerCase").asBoolean()) continue;
            String question = testCase.path("question").asText();
            BenefitChatPlan plan = planner.plan(request(question), null, null);
            for (String partner : plan.partners()) {
                assertThat(catalog.isCatalogName(partner)).as("invented partner for %s: %s", testCase.path("id").asText(), partner)
                        .isTrue();
            }
        }
    }

    @Test
    void everyFrozenCatalogCanonicalNameSurvivesPlannerNormalization() throws Exception {
        PartnerCatalogResolver catalog = (PartnerCatalogResolver) ReflectionTestUtils.getField(planner, "partnerCatalogResolver");

        for (PartnerCatalogResolver.Name name : catalog.names()) {
            BenefitChatPlan plan = planner.plan(request(name.canonical() + " 혜택"), null, null);
            assertThat(plan.partners()).as("planner partner for %s", name.canonical()).contains(name.canonical());
        }
    }

    @Test
    void modelCannotInventPartnerCategoryChannelOrOtherConstraints() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"스타벅스 혜택\",\"categories\":[\"쇼핑\"],\"partners\":[\"스타벅스\"],\"excludedPartners\":[\"스타벅스\"],\"carriers\":[\"KT\"],\"grade\":\"VIP\",\"compareCarriers\":false,\"channel\":\"OFFLINE\",\"nearby\":true,\"location\":\"강남\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));

        BenefitChatPlan plan = enabledPlanner(model).plan(request("혜택"), null, null);

        assertThat(plan.query()).contains("혜택");
        assertThat(plan.categories()).containsExactly("멤버십 혜택");
        assertThat(plan.partners()).isEmpty();
        assertThat(plan.excludedPartners()).isEmpty();
        assertThat(plan.carriers()).isEmpty();
        assertThat(plan.grade()).isNull();
        assertThat(plan.channel()).isEqualTo("ANY");
        assertThat(plan.nearby()).isFalse();
        assertThat(plan.location()).isEmpty();
    }

    @Test
    void explicitUnknownModelPartnerIsKeptForNoResultSearch() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"없는브랜드 할인\",\"categories\":[],\"partners\":[\"없는브랜드\"],\"excludedPartners\":[],\"carriers\":[],\"grade\":null,\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));

        BenefitChatPlan plan = enabledPlanner(model).plan(request("없는브랜드 혜택"), null, null);

        assertThat(plan.partners()).containsExactly("없는브랜드");
        assertThat(plan.query()).contains("없는브랜드");
    }

    @Test
    void doesNotConfuseCarrierAndGradeSubstringsAndDoesNotCarryUnrelatedShortQuestion() {
        BenefitChatPlan movie = planner.plan(request("영화 할인"), Carrier.KT, Grade.KT_VVIP);
        BenefitChatPlan unrelated = planner.plan(request("날씨 어때?", new ChatMessage("user", "영화 할인")), Carrier.KT, Grade.KT_VVIP);
        BenefitChatPlan vipOnly = planner.plan(request("VIP 혜택"), null, null);
        BenefitChatPlan candy = planner.plan(request("아이스크림 혜택"), null, null);

        assertThat(movie.carriers()).containsExactly(Carrier.KT);
        assertThat(movie.grade()).isEqualTo(Grade.KT_VVIP);
        assertThat(unrelated.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(unrelated.followUp()).isFalse();
        assertThat(vipOnly.carriers()).isEmpty();
        assertThat(vipOnly.grade()).isNull();
        assertThat(candy.categories()).contains("디저트");
    }

    @Test
    void negationsLeaveOnlyPositiveTopicCarrierAndChannelInSearchQuery() {
        BenefitChatPlan topic = planner.plan(request("영화 말고 카페 혜택"), null, null);
        BenefitChatPlan channel = planner.plan(request("온라인 말고 매장 혜택"), null, null);
        BenefitChatPlan carrier = planner.plan(request("KT 말고 SKT 혜택"), null, null);
        BenefitChatPlan partner = planner.plan(request("CGV는 빼고 영화 할인"), null, null);

        assertThat(topic.query()).contains("카페").doesNotContain("영화");
        assertThat(topic.categories()).containsExactly("카페/디저트");
        assertThat(channel.query()).contains("매장").doesNotContain("온라인");
        assertThat(channel.channel()).isEqualTo("OFFLINE");
        assertThat(carrier.query()).contains("SKT").doesNotContain("KT 말고");
        assertThat(carrier.carriers()).containsExactly(Carrier.SKT);
        assertThat(partner.excludedPartners()).contains("CGV");
        assertThat(partner.partners()).doesNotContain("CGV");
    }

    @Test
    void membershipCorrectionUpdatesGradeAndUnsupportedSktVvipNeedsClarification() {
        BenefitChatPlan corrected = planner.plan(request("VIP 아니고 골드"), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan unsupported = planner.plan(request("SKT VVIP 영화 혜택"), null, null);

        assertThat(corrected.grade()).isEqualTo(Grade.KT_GOLD);
        assertThat(unsupported.mode()).isEqualTo("CLARIFY");
        assertThat(unsupported.grade()).isNull();
        assertThat(unsupported.clarification()).contains("등급");
    }

    @Test
    void ordinalAndConversationalDetailFollowUpsReuseThePreviousUserTopic() {
        BenefitChatPlan first = planner.plan(request("첫 번째 혜택", new ChatMessage("user", "영화 할인")), null, null);
        BenefitChatPlan second = planner.plan(request("2번째꺼", new ChatMessage("user", "영화 할인")), null, null);
        BenefitChatPlan how = planner.plan(request("그거 어떻게 써?", new ChatMessage("user", "영화 할인")), null, null);

        assertThat(first.followUp()).isTrue();
        assertThat(first.mode()).isEqualTo("DETAIL");
        assertThat(first.query()).contains("영화", "첫 번째");
        assertThat(second.mode()).isEqualTo("DETAIL");
        assertThat(second.query()).contains("영화", "2번째");
        assertThat(how.mode()).isEqualTo("DETAIL");
        assertThat(how.query()).contains("영화", "어떻게 써");
    }

    @Test
    void bareKnownPartnersRemainSearchableAndTopicQueriesPutCanonicalTermsFirst() {
        BenefitChatPlan cgv = planner.plan(request("CGV"), null, null);
        BenefitChatPlan flo = planner.plan(request("FLO"), null, null);
        BenefitChatPlan theater = planner.plan(request("영화관 정보알려줘"), null, null);
        BenefitChatPlan benefit = planner.plan(request("영화혜택좀 알려줘"), null, null);
        BenefitChatPlan unknownBrand = planner.plan(request("Foo 영화 할인"), null, null);

        assertThat(cgv.mode()).isEqualTo("SEARCH");
        assertThat(cgv.partners()).contains("CGV");
        assertThat(cgv.query()).contains("CGV", "멤버십 혜택");
        assertThat(flo.mode()).isEqualTo("SEARCH");
        assertThat(flo.partners()).contains("FLO");
        assertThat(theater.query()).startsWith("영화 혜택").contains("영화관 정보알려줘");
        assertThat(benefit.query()).startsWith("영화 혜택").contains("영화혜택좀 알려줘");
        assertThat(unknownBrand.query()).contains("Foo");
    }

    @Test
    void shortBenefitSectorsAndConvenienceBrandsReachLexicalSearchWithoutInventingResults() {
        BenefitChatPlan academy = planner.plan(request("학원"), null, null);
        BenefitChatPlan cu = planner.plan(request("CU"), null, null);
        BenefitChatPlan koreanCu = planner.plan(request("씨유"), null, null);
        BenefitChatPlan koreanGs25 = planner.plan(request("지에스25"), null, null);
        BenefitChatPlan parcel = planner.plan(request("택배"), null, null);
        BenefitChatPlan unknown = planner.plan(request("Zylo"), null, null);

        assertThat(academy.mode()).isEqualTo("SEARCH");
        assertThat(academy.categories()).containsExactly("교육");
        assertThat(academy.query()).contains("학원");
        assertThat(cu.mode()).isEqualTo("SEARCH");
        assertThat(cu.partners()).contains("CU");
        assertThat(koreanCu.partners()).contains("CU");
        assertThat(koreanGs25.partners()).contains("GS25");
        assertThat(parcel.mode()).isEqualTo("SEARCH");
        assertThat(parcel.query()).isEqualTo("택배");
        assertThat(unknown.mode()).isEqualTo("SEARCH");
        assertThat(unknown.query()).contains("Zylo");
    }

    @Test
    void greetingsAndClearlyUnrelatedQuestionsStayOutOfScopeAndEducationNegationIsPreserved() {
        BenefitChatPlan greeting = planner.plan(request("안녕"), null, null);
        BenefitChatPlan englishGreeting = planner.plan(request("hi"), null, null);
        BenefitChatPlan weather = planner.plan(request("날씨 어때?"), null, null);
        BenefitChatPlan brandContainingGreetingToken = planner.plan(request("Shilla"), null, null);
        BenefitChatPlan excludedAcademy = planner.plan(request("학원 말고 영화 혜택"), null, null);

        assertThat(greeting.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(englishGreeting.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(weather.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(brandContainingGreetingToken.mode()).isEqualTo("SEARCH");
        assertThat(brandContainingGreetingToken.query()).contains("Shilla");
        assertThat(excludedAcademy.categories()).containsExactly("영화");
        assertThat(excludedAcademy.query()).doesNotContain("학원");
    }

    @Test
    void gradeOnlyFollowUpKeepsEarlierMovieTopicAndUserCarrier() {
        BenefitChatPlan plan = planner.plan(request("나 골드야", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 KT야")), null, null);

        assertThat(plan.followUp()).isTrue();
        assertThat(plan.query()).contains("영화", "골드");
        assertThat(plan.categories()).contains("영화");
        assertThat(plan.carriers()).containsExactly(Carrier.KT);
        assertThat(plan.grade()).isEqualTo(Grade.KT_GOLD);
    }

    @Test
    void multiTurnMembershipUsesActiveCarrierForSharedVipAliasAndResetsOnCarrierCorrection() {
        List<ChatMessage> movieAndKt = List.of(new ChatMessage("user", "영화 할인"), new ChatMessage("user", "나 KT야"));
        BenefitChatPlan vip = planner.plan(new BenefitChatRequest("VIP야", movieAndKt, List.of(), null, null, null, null), null, null);
        BenefitChatPlan repeatedKt = planner.plan(new BenefitChatRequest("나 KT야", append(movieAndKt, "VIP야"), List.of(), null, null, null, null), null, null);
        BenefitChatPlan correctedCarrier = planner.plan(new BenefitChatRequest("이번에는 SKT야", append(movieAndKt, "VIP야", "나 KT야"), List.of(), null, null, null, null), null, null);
        BenefitChatPlan sktVip = planner.plan(new BenefitChatRequest("VIP야", append(movieAndKt, "VIP야", "나 KT야", "이번에는 SKT야"), List.of(), null, null, null, null), null, null);
        BenefitChatPlan lguVip = planner.plan(request("LG유플러스 VIP 영화 혜택"), null, null);
        BenefitChatPlan unsupportedForeignGrade = planner.plan(new BenefitChatRequest("SKT VVIP야", movieAndKt, List.of(), null, null, null, null), null, null);

        assertThat(vip.followUp()).isTrue();
        assertThat(vip.query()).contains("영화");
        assertThat(vip.carriers()).containsExactly(Carrier.KT);
        assertThat(vip.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(repeatedKt.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(correctedCarrier.carriers()).containsExactly(Carrier.SKT);
        assertThat(correctedCarrier.categories()).contains("영화");
        assertThat(correctedCarrier.grade()).isNull();
        assertThat(sktVip.carriers()).containsExactly(Carrier.SKT);
        assertThat(sktVip.grade()).isEqualTo(Grade.SKT_VIP);
        assertThat(lguVip.grade()).isEqualTo(Grade.VIP);
        assertThat(unsupportedForeignGrade.mode()).isEqualTo("CLARIFY");
        assertThat(unsupportedForeignGrade.grade()).isNull();
    }

    @Test
    void followUpKeepsMovieExclusionAndChannelUntilReplacementOrTopicChange() {
        List<ChatMessage> firstTurn = List.of(new ChatMessage("user", "영화 할인, CGV 말고 온라인"));
        BenefitChatPlan otherPartner = planner.plan(new BenefitChatRequest("다른 곳", firstTurn, List.of(), null, null, null, null), null, null);
        BenefitChatPlan offline = planner.plan(new BenefitChatRequest("매장에서는?", append(firstTurn, "다른 곳"), List.of(), null, null, null, null), null, null);
        BenefitChatPlan cafe = planner.plan(new BenefitChatRequest("이번에는 카페 혜택", append(firstTurn, "다른 곳", "매장에서는?", "나 KT VIP야"), List.of(), null, null, null, null), null, null);

        assertThat(otherPartner.followUp()).isTrue();
        assertThat(otherPartner.categories()).contains("영화");
        assertThat(otherPartner.excludedPartners()).contains("CGV");
        assertThat(otherPartner.channel()).isEqualTo("ONLINE");
        assertThat(offline.excludedPartners()).contains("CGV");
        assertThat(offline.channel()).isEqualTo("OFFLINE");
        assertThat(cafe.followUp()).isFalse();
        assertThat(cafe.categories()).containsExactly("카페/디저트");
        assertThat(cafe.partners()).doesNotContain("CGV");
        assertThat(cafe.excludedPartners()).doesNotContain("CGV");
        assertThat(cafe.channel()).isEqualTo("ANY");
        assertThat(cafe.carriers()).containsExactly(Carrier.KT);
        assertThat(cafe.grade()).isEqualTo(Grade.KT_VIP);
    }

    @Test
    void separateFilterAndChannelTurnsCarryForwardIntoAlternativeQuestion() {
        List<ChatMessage> turns = List.of(new ChatMessage("user", "영화 할인"), new ChatMessage("user", "CGV 말고"),
                new ChatMessage("user", "온라인으로"));

        BenefitChatPlan plan = planner.plan(new BenefitChatRequest("다른 곳은?", turns, List.of(), null, null, null, null), null, null);

        assertThat(plan.followUp()).isTrue();
        assertThat(plan.categories()).containsExactly("영화");
        assertThat(plan.excludedPartners()).contains("CGV");
        assertThat(plan.partners()).doesNotContain("CGV");
        assertThat(plan.channel()).isEqualTo("ONLINE");
    }

    @Test
    void recognizedPartnerIsRetainedAcrossMembershipRefinementsButExplicitBrandStartsItsOwnTopic() {
        List<ChatMessage> turns = List.of(new ChatMessage("user", "영화 할인"), new ChatMessage("user", "GS25"),
                new ChatMessage("user", "나 KT야"));
        BenefitChatPlan grade = planner.plan(new BenefitChatRequest("VIP야", turns, List.of(), null, null, null, null), null, null);
        BenefitChatPlan brandTopic = planner.plan(request("GS25", new ChatMessage("user", "영화 할인")), null, null);

        assertThat(grade.categories()).isEmpty();
        assertThat(grade.partners()).containsExactly("GS25");
        assertThat(grade.query()).contains("GS25").doesNotContain("영화");
        assertThat(grade.carriers()).containsExactly(Carrier.KT);
        assertThat(grade.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(brandTopic.categories()).isEmpty();
        assertThat(brandTopic.partners()).containsExactly("GS25");
        assertThat(brandTopic.query()).contains("GS25").doesNotContain("영화 혜택");
    }

    @Test
    void explicitPartnerBoundaryResetsEveryOlderTopicFilter() {
        List<ChatMessage> history = List.of(new ChatMessage("user", "영화 할인, 온라인 강남역 근처 CGV 말고"),
                new ChatMessage("user", "GS25"));

        BenefitChatPlan plan = planner.plan(new BenefitChatRequest("나 KT야", history, List.of(), null, null, null, null), null, null);

        assertThat(plan.categories()).isEmpty();
        assertThat(plan.partners()).containsExactly("GS25");
        assertThat(plan.carriers()).containsExactly(Carrier.KT);
        assertThat(plan.channel()).isEqualTo("ANY");
        assertThat(plan.nearby()).isFalse();
        assertThat(plan.location()).isEmpty();
        assertThat(plan.excludedPartners()).isEmpty();
    }

    @Test
    void genericFollowUpAfterTopicChangeDoesNotRestoreOldFiltersOrLocation() {
        List<ChatMessage> history = List.of(new ChatMessage("user", "영화 할인, CGV 말고 온라인 강남 근처"),
                new ChatMessage("user", "이번에는 카페 혜택"));

        BenefitChatPlan plan = planner.plan(new BenefitChatRequest("다른 곳은?", history, List.of(), null, null, null, null), null, null);

        assertThat(plan.followUp()).isTrue();
        assertThat(plan.categories()).containsExactly("카페/디저트");
        assertThat(plan.excludedPartners()).doesNotContain("CGV");
        assertThat(plan.channel()).isEqualTo("ANY");
        assertThat(plan.nearby()).isFalse();
        assertThat(plan.location()).isEmpty();
    }

    @Test
    void llmCannotRestoreOldFiltersAfterExplicitTopicReset() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"영화 근처 강남\",\"categories\":[\"영화\"],\"partners\":[\"CGV\"],\"excludedPartners\":[\"CGV\"],\"carriers\":[],\"grade\":null,\"compareCarriers\":false,\"channel\":\"OFFLINE\",\"nearby\":true,\"location\":\"강남\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":true}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);
        BenefitChatRequest request = request("이번에는 카페 혜택", new ChatMessage("user", "영화 할인, CGV 말고 온라인 강남 근처"));

        BenefitChatPlan plan = enabledPlanner.plan(request, null, null);

        assertThat(plan.categories()).containsExactly("카페/디저트");
        assertThat(plan.partners()).isEmpty();
        assertThat(plan.excludedPartners()).isEmpty();
        assertThat(plan.channel()).isEqualTo("ANY");
        assertThat(plan.nearby()).isFalse();
        assertThat(plan.location()).isEmpty();
        assertThat(plan.followUp()).isFalse();
    }

    @Test
    void llmStaleAssistantClaimsCannotOverrideCurrentVipTurnOrItsActiveTopic() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"SKT VVIP 영화\",\"categories\":[\"영화\"],\"partners\":[],\"excludedPartners\":[],\"carriers\":[\"SKT\"],\"grade\":\"SKT_VIP\",\"compareCarriers\":false,\"channel\":\"OFFLINE\",\"nearby\":false,\"location\":\"\",\"mode\":\"CLARIFY\",\"clarification\":\"등급 확인\",\"followUp\":false}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);
        BenefitChatPlan plan = enabledPlanner.plan(request("VIP야", new ChatMessage("user", "영화 할인"),
                new ChatMessage("assistant", "SKT VVIP 영화 할인입니다."), new ChatMessage("user", "나 KT야")), null, null);

        assertThat(plan.query()).contains("영화");
        assertThat(plan.categories()).containsExactly("영화");
        assertThat(plan.carriers()).containsExactly(Carrier.KT);
        assertThat(plan.grade()).isEqualTo(Grade.KT_VIP);
        assertThat(plan.mode()).isNotEqualTo("CLARIFY");
        assertThat(plan.channel()).isEqualTo("ANY");
    }

    private static List<ChatMessage> append(List<ChatMessage> messages, String... userTexts) {
        return java.util.stream.Stream.concat(messages.stream(), java.util.Arrays.stream(userTexts)
                .map(text -> new ChatMessage("user", text))).toList();
    }

    @Test
    void enabledModelCanResolveHeuristicallyOutOfScopeNaturalRequest() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"여가 멤버십 혜택\",\"categories\":[\"여가/여행\"],\"partners\":[],\"excludedPartners\":[],\"carriers\":[],\"grade\":null,\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);

        BenefitChatPlan plan = enabledPlanner.plan(request("심심한데 뭐 하지"), null, null);

        org.mockito.Mockito.verify(model).stream(any(Prompt.class));
        assertThat(plan.mode()).isEqualTo("SEARCH");
        assertThat(plan.categories()).doesNotContain("여가/여행");
        assertThat(plan.query()).contains("심심한데 뭐 하지").doesNotContain("여가");
    }

    @Test
    void explicitCurrentConvenienceTopicOverridesStaleNearbyLlmQueryAndFilters() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"우리집 근처에서 인기있는 혜택\",\"categories\":[],\"partners\":[\"스타벅스\"],\"excludedPartners\":[],\"carriers\":[],\"grade\":null,\"compareCarriers\":false,\"channel\":\"OFFLINE\",\"nearby\":true,\"location\":\"강남\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":true}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);

        BenefitChatPlan plan = enabledPlanner.plan(request("편의점", new ChatMessage("user", "우리집 근처에서 인기있는 혜택")), null, null);

        assertThat(plan.query()).startsWith("편의점 혜택");
        assertThat(plan.categories()).containsExactly("편의점");
        assertThat(plan.partners()).isEmpty();
        assertThat(plan.channel()).isEqualTo("ANY");
        assertThat(plan.nearby()).isFalse();
        assertThat(plan.location()).isEmpty();
        assertThat(plan.followUp()).isFalse();
    }

    @Test
    void enabledModelGradeCorrectionUsesHistoricalCarrierBeforeProfileCarrier() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"영화 혜택 나 골드야\",\"categories\":[\"영화\"],\"partners\":[],\"excludedPartners\":[],\"carriers\":[\"SKT\"],\"grade\":\"SKT_GOLD\",\"compareCarriers\":false,\"channel\":\"ANY\",\"nearby\":false,\"location\":\"\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":true}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);
        BenefitChatRequest request = request("나 골드야", new ChatMessage("user", "영화 할인"), new ChatMessage("user", "나 KT야"));

        BenefitChatPlan plan = enabledPlanner.plan(request, Carrier.SKT, Grade.SKT_GOLD);

        assertThat(plan.carriers()).containsExactly(Carrier.KT);
        assertThat(plan.grade()).isEqualTo(Grade.KT_GOLD);
    }

    @Test
    void unsupportedGradeUsesHistoricalCarrierAndClarificationModesAlwaysHaveText() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);
        BenefitChatPlan unsupported = enabledPlanner.plan(request("VVIP", new ChatMessage("user", "영화 할인"),
                new ChatMessage("user", "나 SKT야")), Carrier.KT, Grade.KT_VIP);
        BenefitChatPlan malformedClarify = new BenefitChatPlan("", List.of(), List.of(), List.of(), List.of(), null,
                false, "ANY", false, "", "CLARIFY", "", false);
        BenefitChatPlan malformedOutOfScope = new BenefitChatPlan("", List.of(), List.of(), List.of(), List.of(), null,
                false, "ANY", false, "", "OUT_OF_SCOPE", "", false);

        org.mockito.Mockito.verifyNoInteractions(model);
        assertThat(unsupported.mode()).isEqualTo("CLARIFY");
        assertThat(unsupported.clarification()).contains("등급");
        assertThat(malformedClarify.clarification()).isNotBlank();
        assertThat(malformedOutOfScope.clarification()).isNotBlank();
    }

    @Test
    void enabledStructuredOutputCannotUndoNegationsInventCarrierOrInferLocationFromCoordinates() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"query\":\"영화 카페 혜택\",\"categories\":[\"영화\",\"카페/디저트\"],\"partners\":[\"CGV\"],\"excludedPartners\":[],\"carriers\":[\"KT\"],\"grade\":\"KT_VIP\",\"compareCarriers\":false,\"channel\":\"ONLINE\",\"nearby\":false,\"location\":\"강남\",\"mode\":\"SEARCH\",\"clarification\":\"\",\"followUp\":false}"))))));
        BenefitChatPlanner enabledPlanner = enabledPlanner(model);

        BenefitChatPlan plan = enabledPlanner.plan(new BenefitChatRequest("영화 말고 카페 혜택", List.of(), List.of(), 37.5, 127.0, null, null), null, null);

        assertThat(plan.query()).contains("카페").doesNotContain("영화");
        assertThat(plan.categories()).containsExactly("카페/디저트");
        assertThat(plan.partners()).doesNotContain("CGV");
        assertThat(plan.carriers()).isEmpty();
        assertThat(plan.grade()).isNull();
        assertThat(plan.location()).isEmpty();
    }

    private static BenefitChatRequest request(String q, ChatMessage... messages) {
        return new BenefitChatRequest(q, List.of(messages), List.of(), null, null, null, null);
    }
}
