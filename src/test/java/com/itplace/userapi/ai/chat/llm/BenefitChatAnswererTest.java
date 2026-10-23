package com.itplace.userapi.ai.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatRequest.ChatMessage;
import com.itplace.userapi.ai.chat.intent.BenefitChatPlan;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitFact;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.reference.TestReferenceDataService;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;

class BenefitChatAnswererTest {
    private static BenefitChatAnswerer createAnswerer(OpenAiChatModel model, ObjectMapper mapper, String chatModel, Duration timeout, boolean enabled) {
        BenefitChatAnswerer answerer = new BenefitChatAnswerer(model, mapper, chatModel, timeout, enabled);
        ReflectionTestUtils.setField(answerer, "referenceDataService", new TestReferenceDataService());
        return answerer;
    }

    private static Candidate partialOfferCandidate(String candidateKey, String partner, String benefitName,
                                                    String grade, BenefitOffer offer) {
        return Candidate.builder().candidateKey(candidateKey).offerKey(offer.key()).partnerName(partner)
                .benefitName(benefitName).businessType("ATTRACTION").carrier("SKT").grade(grade)
                .usageType(offer.usageType().name())
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.PARTIAL, null)).build();
    }

    @Test
    void noEvidenceProducesNoInventedCandidateKeysAndSkipsProvider() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "", Duration.ofSeconds(1), true);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("월 몇 번?"), plan(), List.of());

        assertThat(answer.candidateKeys()).isEmpty();
        assertThat(answer.reason()).contains("찾지 못");
        verifyNoInteractions(model);
    }

    @Test
    void malformedOrUnsupportedModelKeysFallBackToEvidenceBasedAnswer() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"월 99회 가능해요\",\"candidateKeys\":[\"unknown\"],\"suggestedQuestions\":[]}"),
                response("not json"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        Candidate c = candidate();

        BenefitChatAnswerer.ChatAnswer unsupported = answerer.answer(request("월 몇 번?"), plan(), List.of(c));
        BenefitChatAnswerer.ChatAnswer malformed = answerer.answer(request("월 몇 번?"), plan(), List.of(c));

        assertThat(unsupported.candidateKeys()).containsExactly(c.getCandidateKey());
        assertThat(unsupported.reason()).contains("혜택 카드에서 확인").doesNotContain("99회", "월 1회");
        assertThat(malformed.candidateKeys()).containsExactly(c.getCandidateKey());
        assertThat(malformed.reason()).contains("혜택 카드에서 확인").doesNotContain("월 1회");
    }

    @Test
    void deterministicAnswerKeepsDistinctKeysAndAvoidsRawConditionDump() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        Candidate a = candidate();
        Candidate b = Candidate.builder().candidateKey("benefit:2:policy:3:tier:4").benefitId(a.getBenefitId())
                .partnerName("CGV").benefitName("콤보 할인").manual("콤보 주문 시 적용").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("월 몇 번?"), plan(), List.of(a, b));

        assertThat(answer.candidateKeys()).containsExactly(a.getCandidateKey(), b.getCandidateKey());
        assertThat(answer.reason()).contains("KT", "VIP", "혜택 카드에서 확인")
                .doesNotContain("KT_VIP", "UNKNOWN", "월 1회", "현장 예매", "특별관 제외");
        assertThat(answer.reason().length()).isLessThan(200);
        assertThat(answer.suggestedQuestions()).allMatch(q -> !q.endsWith("알려드릴까요?"));
    }

    @Test
    void searchAnswerStaysBriefEvenWhenModelReturnsDetailedGroundedText() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"CGV는 월 이용 횟수와 현장 예매 조건을 확인해야 하며 자세한 제한은 원문을 살펴보세요.\",\"candidateKeys\":[\"benefit:1:policy:2:tier:3\"],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan search = new BenefitChatPlan("편의점 혜택", List.of("편의점"), List.of(), List.of(),
                List.of(), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate c = candidate();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("편의점"), search, List.of(c));

        assertThat(answer.candidateKeys()).containsExactly(c.getCandidateKey());
        assertThat(answer.reason()).contains("CGV", "카드에서 자세히 확인")
                .doesNotContain("횟수", "현장 예매", "원문");
        assertThat(answer.reason().length()).isLessThan(100);
    }

    @Test
    void answererUsesStructuredFactsWithoutSendingThemToTheModelWhenNoSourceClauseExists() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String reason = "이마트24는 1,000원당 50원 할인돼요. 이용 채널은 오프라인이에요.";
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"" + reason
                + "\",\"candidateKeys\":[\"benefit:11:policy:21:tier:31\"],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitOffer offer = new BenefitOffer("offline-discount", "이마트24 할인", "", UsageType.OFFLINE,
                List.of("BASIC"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, null, "1,000원당 50원 할인", null)));
        Candidate candidate = Candidate.builder().candidateKey("benefit:11:policy:21:tier:31")
                .partnerName("이마트24").benefitName("멤버십 할인").carrier("LGU").grade("BASIC")
                .usageType("OFFLINE").conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("이마트24 할인 금액", List.of("편의점"), List.of("이마트24"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.BASIC, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("할인 금액은 얼마고 최대 한도와 사용 횟수는?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("이마트24", "50원 할인 (1000원당)")
                .doesNotContain("이용 채널은 오프라인", "1,000원당 50원 할인돼요");
        verifyNoInteractions(model);
    }

    @Test
    void summaryIncludesOnlyScopedStructuredFactsAndAvoidsGenericBenefitIntro() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("bori-kt", "보리보리 KT 멤버십", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("10"), "PERCENT", null, null,
                                null, null, null, "10% 할인 쿠폰", null),
                        new BenefitFact("MIN_SPEND", new BigDecimal("20000"), "KRW", null, null,
                                null, null, "MIN", "- 배송비 제외 20,000원 이상 구매 시 사용 가능 (최대 할인금액 3,000원)", null),
                        new BenefitFact("DISCOUNT_CAP", new BigDecimal("3000"), "KRW", null, null,
                                null, null, "MAX", "- 배송비 제외 20,000원 이상 구매 시 사용 가능 (최대 할인금액 3,000원)", null)));
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("보리보리 KT 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 혜택 요약", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("보리보리에서 얼마를 결제할 때 얼마가 할인되는 구조야?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("10% 할인", "배송비 제외 20,000원 이상 구매 시 사용 가능 (최대 할인금액 3,000원)")
                .doesNotContain("혜택 혜택", "확인해 보세요");
        assertThat(answer.candidateKeys()).containsExactly("bori-kt");
    }

    @Test
    void namedPartnerSearchSummaryIncludesVerifiedRate() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("flower-kt", "어니스트플라워 KT 멤버십", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("15"), "PERCENT", null,
                        null, null, null, null, "15% 할인 쿠폰", null)));
        Candidate flower = Candidate.builder().candidateKey("flower-kt").partnerName("어니스트플라워")
                .benefitName("어니스트플라워 KT 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan searchSummary = new BenefitChatPlan("어니스트플라워 KT 혜택 요약", List.of(),
                List.of("어니스트플라워"), List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "SEARCH", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("어니스트플라워 (KT)에서 받을 수 있는 멤버십 혜택을 요약해줘."), searchSummary, List.of(flower));

        assertThat(answer.reason()).contains("15% 할인").doesNotContain("혜택 혜택");
    }

    @Test
    void alternativeStarbucksDiscountFactsAreNotFlattenedIntoStackedAmounts() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("starbucks-vip-coupon", "스타벅스 VIP콕", "", UsageType.BOTH,
                List.of("VVIP"), false,
                List.of("- 아메리카노 Tall 사이즈 (HOT/ICED) 선택 가능 또는 음료 4,700원 할인",
                        "* 1,400원 더블 사이즈업 한정하여 적용 가능하며, 더블 사이즈업 1,600원 음료의 경우 1,400원 할인으로 적용"),
                List.of(), List.of("VIP콕 통합 월 1회"), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("4700"), "KRW", null, null,
                                null, null, null, "음료 4,700원 할인", null),
                        new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("1400"), "KRW", null, null,
                                null, null, "MAX", "1,400원 더블 사이즈업 한정", null)));
        Candidate starbucks = Candidate.builder().candidateKey("starbucks-vip-coupon").partnerName("스타벅스")
                .benefitName("스타벅스 VIP콕").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("스타벅스 핵심 혜택", List.of(), List.of("스타벅스"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VVIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("스타벅스 VIP콕의 핵심 혜택을 한 문장으로 알려줘."), detail, List.of(starbucks));

        assertThat(answer.reason()).contains("또는", "4,700원 할인")
                .doesNotContain("4,700원 할인, 1400원 할인", "4700원 할인, 1400원 할인");
    }

    @Test
    void cinemaHallDiscountIsNotAttributedToReserveOffer() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String regularHallSource = "- 일반관/리클라이너관은 1일 1회 최대 8매까지 9천원에 예매 가능합니다.(정상가 대비 최대 6천원 할인)";
        String reserveHallSource = "- 리저브관은 1일 1회 최대 2매까지 1만2천원에 예매 가능합니다.(정상가 대비 최대 4천원 할인)";
        BenefitOffer regular = new BenefitOffer("cineq-regular", "씨네Q 일반관/리클라이너관", "", UsageType.ONLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(regularHallSource), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_CAP", new BigDecimal("6000"), "KRW", null, null,
                        null, null, "MAX", regularHallSource, null)));
        BenefitOffer reserve = new BenefitOffer("cineq-reserve", "씨네Q 리저브관", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(regularHallSource, reserveHallSource), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_CAP", new BigDecimal("6000"), "KRW", null, null,
                        null, null, "MAX", regularHallSource, null)));
        Candidate cineq = Candidate.builder().candidateKey("cineq-reserve").offerKey("cineq-reserve").partnerName("씨네Q")
                .benefitName("씨네Q 리저브관").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regular, reserve), OfferResolution.PARTIAL, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("씨네Q 리저브관 할인", List.of(), List.of("씨네Q"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("씨네Q 리저브관 최대 할인 금액은 얼마야?"), detail, List.of(cineq));

        assertThat(answer.reason()).contains(reserveHallSource)
                .doesNotContain(regularHallSource, "리저브관 6,000원", "리저브관 최대 6,000원", "리저브관은 6,000원");
        assertThat(answer.candidateKeys()).containsExactly("cineq-reserve");
    }

    @Test
    void partialLinkedCineQOffersQuoteTheirOwnHallConditions() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String regularClause = "- 일반관/리클라이너관은 1일 1회 최대 8매까지 9천원에 예매 가능합니다.(정상가 대비 최대 6천원 할인)";
        String reserveClause = "- 리저브관은 1일 1회 최대 2매까지 1만2천원에 예매 가능합니다.(정상가 대비 최대 4천원 할인)";
        BenefitOffer regular = new BenefitOffer("offer-bf73cbfcecc05137", "씨네Q 일반관/리클라이너관", "", UsageType.ONLINE,
                List.of("VVIP", "VIP", "BASIC"), false, List.of(), List.of(), List.of(regularClause),
                List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer reserve = new BenefitOffer("offer-886883d67dd052b0", "씨네Q 리저브관", "", UsageType.ONLINE,
                List.of("VVIP", "VIP", "BASIC"), false, List.of(), List.of(), List.of(reserveClause),
                List.of(), List.of(), List.of(), null, List.of());
        Candidate regularCandidate = Candidate.builder().candidateKey("cineq-regular")
                .offerKey("offer-bf73cbfcecc05137").partnerName("씨네Q")
                .benefitName("씨네Q 일반관/리클라이너관").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regular), OfferResolution.PARTIAL, null)).build();
        Candidate reserveCandidate = Candidate.builder().candidateKey("cineq-reserve")
                .offerKey("offer-886883d67dd052b0").partnerName("씨네Q")
                .benefitName("씨네Q(리저브관) LGU+ 멤버십").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(reserve), OfferResolution.PARTIAL, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("씨네Q 할인 조건", List.of(), List.of("씨네Q"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VVIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("씨네Q 각 상영관의 최대 할인 금액은?"), detail, List.of(regularCandidate, reserveCandidate));

        assertThat(answer.reason())
                .contains("씨네Q 일반관/리클라이너관: “" + regularClause + "”",
                        "씨네Q(리저브관) LGU+ 멤버십 · 씨네Q 리저브관: “" + reserveClause + "”")
                .doesNotContain("리저브관은 1일 1회 최대 8매", "일반관/리클라이너관은 1일 1회 최대 2매");
        assertThat(answer.candidateKeys()).containsExactly("cineq-regular", "cineq-reserve");
    }

    @Test
    void exactSourceFallbackAnswersMissingDiscountEvenWhenOnlyUsageFactWasStructured() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String discountClause = "- 리저브관은 정상가 대비 최대 4천원 할인";
        BenefitOffer offer = new BenefitOffer("cinema", "씨네Q 리저브관", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(discountClause),
                List.of("일 1회"), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "DAY", null,
                        null, null, null, "일 1회", null)));
        Candidate candidate = Candidate.builder().candidateKey("cinema").partnerName("씨네Q")
                .benefitName("씨네Q 리저브관").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("씨네Q 리저브관 할인", List.of(), List.of("씨네Q"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("씨네Q 리저브관 최대 할인 금액은?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains(discountClause).doesNotContain("4000원 할인입니다");
    }

    @Test
    void exactSourceFallbackAbstainsOnMixedGradesOrMultipleEligibleOffers() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String mixedGrade = "VVIP는 15% 할인, 우수 등급은 10% 할인";
        BenefitOffer mixedGradeOffer = new BenefitOffer("mixed", "테스트 제휴 혜택", "", UsageType.OFFLINE,
                List.of("VVIP"), false, List.of(), List.of(), List.of(mixedGrade), List.of(), List.of(), List.of(), null, List.of());
        Candidate vvip = Candidate.builder().candidateKey("mixed").partnerName("테스트 제휴")
                .benefitName("테스트 제휴 혜택").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(mixedGradeOffer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("테스트 제휴 할인", List.of(), List.of("테스트 제휴"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VVIP, false, "OFFLINE", false, "", "DETAIL", "", false);
        BenefitChatAnswerer.ChatAnswer mixed = answerer.answer(
                request("테스트 제휴 할인율은 얼마야?"), detail, List.of(vvip));

        BenefitOffer first = new BenefitOffer("first", "테스트 제휴 1", "", UsageType.OFFLINE,
                List.of("VVIP"), false, List.of(), List.of(), List.of("첫 혜택은 최대 5천원 할인"), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer second = new BenefitOffer("second", "테스트 제휴 2", "", UsageType.OFFLINE,
                List.of("VVIP"), false, List.of(), List.of(), List.of("두 번째 혜택은 최대 7천원 할인"), List.of(), List.of(), List.of(), null, List.of());
        Candidate ambiguous = Candidate.builder().candidateKey("ambiguous").partnerName("테스트 제휴")
                .benefitName("테스트 제휴 혜택").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(first, second), OfferResolution.SCOPED, null)).build();
        BenefitChatAnswerer.ChatAnswer multiple = answerer.answer(
                request("테스트 제휴 할인 금액은?"), detail, List.of(ambiguous));
        Candidate unlinkedPartial = Candidate.builder().candidateKey("unlinked").partnerName("테스트 제휴")
                .benefitName("테스트 제휴 혜택").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(first), OfferResolution.PARTIAL, null)).build();
        BenefitChatAnswerer.ChatAnswer unlinked = answerer.answer(
                request("테스트 제휴 할인 금액은?"), detail, List.of(unlinkedPartial));

        assertThat(mixed.reason()).doesNotContain("15%", "10%");
        assertThat(multiple.reason()).doesNotContain("5천원", "7천원");
        assertThat(unlinked.reason()).doesNotContain("5천원");
    }

    @Test
    void sourceClauseFallbackDoesNotExpandBroadSearchCards() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String source = "리저브관은 정상가 대비 최대 4천원 할인";
        BenefitOffer offer = new BenefitOffer("cinema", "씨네Q 리저브관", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(source), List.of(), List.of(), List.of(), null, List.of());
        Candidate candidate = Candidate.builder().candidateKey("cinema").partnerName("씨네Q")
                .benefitName("씨네Q 리저브관").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan search = new BenefitChatPlan("씨네Q 혜택", List.of(), List.of("씨네Q"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "OFFLINE", false, "", "SEARCH", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("씨네Q 혜택"), search, List.of(candidate));

        assertThat(answer.reason()).doesNotContain(source).contains("카드에서 자세히");
    }

    @Test
    void usageQuestionSkipsEarlierProductDiscountAndQuotesUsageInstruction() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String discountProduct = "상품 구매 시 최대 5천원 할인";
        String usageInstruction = "바코드를 제시하고 결제해 주세요";
        BenefitOffer offer = new BenefitOffer("usage", "제휴처 혜택", discountProduct + "\n" + usageInstruction,
                UsageType.OFFLINE, List.of("KT_VIP"), false, List.of(discountProduct), List.of(), List.of(), List.of(usageInstruction),
                List.of(), List.of(), null, List.of());
        Candidate candidate = Candidate.builder().candidateKey("usage").partnerName("제휴처")
                .benefitName("제휴처 혜택").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("제휴처 이용 방법", List.of(), List.of("제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("제휴처 혜택은 어떻게 사용해?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains(usageInstruction).doesNotContain(discountProduct);
    }

    @Test
    void exactSourceFallbackKeepsMinimumSpendExclusionAndUsageClausesRelevant() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String minimum = "- 배송비 제외 20,000원 이상 구매 시 사용 가능 (최대 할인금액 3,000원)";
        String exclusion = "- 배달앱 주문은 사용할 수 없습니다.";
        String usage = "쿠폰 번호 발급 후 홈페이지에서 등록하여 사용";
        BenefitOffer offer = new BenefitOffer("detail", "보리보리 멤버십", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of(minimum),
                List.of(usage), List.of(exclusion), List.of(), null, List.of());
        Candidate candidate = Candidate.builder().candidateKey("detail").partnerName("보리보리")
                .benefitName("보리보리 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 조건", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer minimumAnswer = answerer.answer(
                request("보리보리 최소 구매 금액과 할인 조건은?"), detail, List.of(candidate));
        BenefitChatAnswerer.ChatAnswer exclusionAnswer = answerer.answer(
                request("보리보리 배달앱 제외 조건은?"), detail, List.of(candidate));
        BenefitChatAnswerer.ChatAnswer usageAnswer = answerer.answer(
                request("보리보리 쿠폰 사용 방법은?"), detail, List.of(candidate));

        assertThat(minimumAnswer.reason()).contains(minimum).doesNotContain("3,000원 할인 한도입니다");
        assertThat(exclusionAnswer.reason()).contains(exclusion);
        assertThat(usageAnswer.reason()).contains(usage);
    }

    @Test
    void sameBrandUsageQuestionQuotesEachOfferInsteadOfReturningDiscountFacts() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer mobile = new BenefitOffer("mobile", "스타벅스 모바일 혜택", "", UsageType.ONLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(),
                List.of("앱 쿠폰을 제시해 결제 시 사용"), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("15"), "PERCENT", null, null,
                        null, null, null, "15% 할인", null)));
        BenefitOffer store = new BenefitOffer("store", "스타벅스 매장 혜택", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(),
                List.of("매장에서 바코드를 제시하고 결제"), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("5"), "PERCENT", null, null,
                        null, null, null, "5% 할인", null)));
        Candidate mobileCandidate = Candidate.builder().candidateKey("mobile").partnerName("스타벅스")
                .benefitName("스타벅스 모바일 혜택").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(mobile), OfferResolution.SCOPED, null)).build();
        Candidate storeCandidate = Candidate.builder().candidateKey("store").partnerName("스타벅스")
                .benefitName("스타벅스 매장 혜택").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(store), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("스타벅스 혜택 이용 방법", List.of(), List.of("스타벅스"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("스타벅스 혜택은 어떻게 사용해?"), detail, List.of(mobileCandidate, storeCandidate));

        assertThat(answer.reason()).contains("앱 쿠폰을 제시해 결제 시 사용", "매장에서 바코드를 제시하고 결제")
                .doesNotContain("15% 할인", "5% 할인");
    }

    @Test
    void gradeScopedDiscountFactsDoNotLeakAcrossGradesInSharedOffer() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("luge-lgu", "스카이라인 루지", "", UsageType.OFFLINE,
                List.of("VVIP", "VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("15"), "PERCENT", null, null,
                                null, null, null, "VVIP/VIP 15% 할인", null),
                        new BenefitFact("DISCOUNT_RATE", new BigDecimal("10"), "PERCENT", null, null,
                                null, null, null, "우수 10% 할인", null)));
        Candidate luge = Candidate.builder().candidateKey("luge-vvip").partnerName("스카이라인 루지")
                .benefitName("스카이라인 루지").carrier("LGU").grade("VVIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("스카이라인 루지 할인", List.of(), List.of("스카이라인 루지"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VVIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("스카이라인 루지 할인율 알려줘"), detail, List.of(luge));

        assertThat(answer.reason()).contains("15% 할인").doesNotContain("10% 할인");
    }

    @Test
    void genericProductAndVenueWordsAreNotMistakenForGradeOrHallVariants() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        for (String source : List.of("일반상품 구매 시 6,000원 할인", "일반매장 이용 시 6,000원 할인",
                "영화관 이용 시 6,000원 할인", "박물관 입장권 6,000원 할인", "전시관 입장권 6,000원 할인",
                "이용약관에 따른 6,000원 할인")) {
            BenefitOffer offer = new BenefitOffer("generic", "제휴 할인", "", UsageType.OFFLINE,
                    List.of("BASIC"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                    List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("6000"), "KRW", null, null,
                            null, null, null, source, null)));
            Candidate candidate = Candidate.builder().candidateKey("generic").partnerName("제휴처")
                    .benefitName("제휴 할인").carrier("LGU").grade("BASIC")
                    .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                            List.of(offer), OfferResolution.SINGLE, null)).build();
            BenefitChatPlan detail = new BenefitChatPlan("제휴 할인", List.of(), List.of("제휴처"), List.of(),
                    List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                    com.itplace.userapi.benefit.entity.enums.Grade.BASIC, false, "ANY", false, "", "DETAIL", "", false);

            BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("제휴 할인 금액은 얼마야?"), detail, List.of(candidate));

            assertThat(answer.reason()).as(source).contains("6000원 할인");
        }
    }

    @Test
    void emptyFactSourceDoesNotSupportSpecificDiscountClaim() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("unknown-source", "제휴 할인", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("6000"), "KRW", null, null,
                        null, null, null, null, null)));
        Candidate candidate = Candidate.builder().candidateKey("unknown-source").partnerName("제휴처")
                .benefitName("제휴 할인").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("제휴 할인", List.of(), List.of("제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("제휴 할인 금액은 얼마야?"), detail, List.of(candidate));

        assertThat(answer.reason()).doesNotContain("6,000원 할인");
    }

    @Test
    void pointRedemptionRateIsNotPresentedAsDiscountRate() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("seoulland-skt", "서울랜드", "", UsageType.OFFLINE,
                List.of("VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("100"), "PERCENT", null, null,
                        null, null, null, "포인트 사용 가능 (100%, 10P 단위)", null)));
        Candidate seoulland = Candidate.builder().candidateKey("seoulland-skt").partnerName("서울랜드")
                .benefitName("서울랜드").carrier("SKT").grade("VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("서울랜드 할인", List.of(), List.of("서울랜드"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("서울랜드 할인율이 얼마야?"), detail, List.of(seoulland));

        assertThat(answer.reason()).doesNotContain("100%", "100퍼센트");
    }

    @Test
    void compactSourceContextPreservesOfferProductRestrictionsInsteadOfBareAmount() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String sourceContext = "전화/화상영어 1만원 할인 (영어사용국, 정규 1개월 20분)";
        BenefitOffer offer = new BenefitOffer("pagoda-kt", "파고다토쿨 KT 멤버십", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("10000"), "KRW", null,
                        null, null, null, null, "전화/화상영어 1만원 할인", null)));
        Candidate pagoda = Candidate.builder().candidateKey("pagoda-kt").partnerName("파고다토쿨")
                .benefitName("파고다토쿨 KT 멤버십").carrier("KT").grade("KT_GENERAL").context(sourceContext)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("파고다토쿨 KT 혜택", List.of(), List.of("파고다토쿨"),
                List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("파고다토쿨 KT 혜택 내용이 뭐야?"), detail, List.of(pagoda));

        assertThat(answer.reason()).contains("10000원 할인")
                .doesNotContain("영어사용국", "정규 1개월 20분");
    }

    @Test
    void simpleAmountAnswerKeepsShortProductRestrictionFromFactSourceText() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String source = "전화/화상영어 1만원 할인 (영어사용국, 정규 1개월 20분)";
        BenefitOffer offer = new BenefitOffer("pagoda-kt", "파고다토쿨 혜택", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("10000"), "KRW", null,
                        null, null, null, null, source, null)));
        Candidate pagoda = Candidate.builder().candidateKey("pagoda-kt").partnerName("파고다토쿨")
                .benefitName("파고다토쿨 KT 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("파고다토쿨 할인", List.of(), List.of("파고다토쿨"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("파고다토쿨 할인 금액은 얼마야?"), detail, List.of(pagoda));

        assertThat(answer.reason()).contains(source, "영어사용국", "정규 1개월 20분")
                .doesNotContain("10000원 할인입니다");
    }

    @Test
    void multipleSamePartnerOffersKeepTheirFactLabelsSeparate() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        Candidate discount = Candidate.builder().candidateKey("culture-discount").partnerName("컬쳐랜드")
                .benefitName("컬쳐랜드 할인형").carrier("SKT").grade("SKT_VIP")
                .conditions(singleFactOffer("discount", "DISCOUNT_RATE", "3", "PERCENT", "3% 할인"))
                .build();
        Candidate reward = Candidate.builder().candidateKey("culture-reward").partnerName("컬쳐랜드")
                .benefitName("컬쳐랜드 적립형").carrier("SKT").grade("SKT_VIP")
                .conditions(singleFactOffer("reward", "REWARD_RATE", "1", "PERCENT", "1% 적립"))
                .build();
        BenefitChatPlan detail = new BenefitChatPlan("컬쳐랜드 혜택", List.of(), List.of("컬쳐랜드"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("컬쳐랜드 할인/적립 혜택을 요약해줘."), detail, List.of(discount, reward));

        assertThat(answer.reason()).contains("컬쳐랜드 할인형 · 컬쳐랜드 혜택: 3% 할인", "컬쳐랜드 적립형 · 컬쳐랜드 혜택: 1% 적립")
                .doesNotContain("4%", "최대 4%");
        assertThat(answer.candidateKeys()).containsExactly("culture-discount", "culture-reward");
    }

    private static BenefitConditions singleFactOffer(String key, String type, String value, String unit, String source) {
        BenefitOffer offer = new BenefitOffer(key, "컬쳐랜드 혜택", "", UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact(type, new BigDecimal(value), unit, null, null, null, null, null, source, null)));
        return new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(offer), OfferResolution.SINGLE, null);
    }

    private static Candidate linkedPartialCandidate(String candidateKey, String partner, String benefit,
                                                    String carrier, String grade, BenefitOffer offer) {
        return Candidate.builder().candidateKey(candidateKey).offerKey(offer.key()).partnerName(partner)
                .benefitName(benefit).carrier(carrier).grade(grade)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.PARTIAL, null)).build();
    }

    @Test
    void couponIssuerAnswerQuotesScopedClauseWithoutReversingIssuerAndRedeemer() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String source = "쿠폰 번호 발급 → '보리보리' 홈페이지/앱 회원가입 또는 로그인 → MY PAGE → 쿠폰 → 쿠폰번호 입력 후 등록 → 상품 결제 시 적용하여 할인";
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("보리보리 KT 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(source), List.of(),
                        List.of(), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 쿠폰 발급", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("보리보리 쿠폰은 어디서 발급받아?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains(source).doesNotContain("KT 앱에서 발급");
    }

    @Test
    void couponInstructionsFromAnotherGradeOfferAreNeverQuoted() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String vipOnly = "VIP 쿠폰은 KT 멤버십 앱에서만 다운로드";
        String offlineOnly = "오프라인 쿠폰은 매장 카운터에서 발급";
        String general = "쿠폰번호 발급 후 보리보리 홈페이지/앱에서 등록해 사용";
        BenefitOffer vipOffer = new BenefitOffer("vip", "VIP 쿠폰", "", UsageType.ONLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(vipOnly), List.of(), List.of(), null, List.of());
        BenefitOffer offlineOffer = new BenefitOffer("general-offline", "오프라인 쿠폰", "", UsageType.OFFLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(offlineOnly), List.of(), List.of(), null, List.of());
        BenefitOffer generalOffer = new BenefitOffer("general", "일반 쿠폰", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(general), List.of(), List.of(), null, List.of());
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("보리보리 KT 멤버십").carrier("KT").grade("KT_GENERAL")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(vipOffer, offlineOffer, generalOffer), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 쿠폰 발급", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("보리보리 쿠폰은 어디서 발급해?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains(general).doesNotContain(vipOnly, offlineOnly);
    }

    @Test
    void shortContextIsNotQuotedWhenTwoOffersMatchTheSameGradeAndChannel() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String context = "할인형 10% 할인 (특정 상품만 적용)";
        BenefitOffer first = new BenefitOffer("a", "할인형 A", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer second = new BenefitOffer("b", "할인형 B", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("보리보리 KT 멤버십").carrier("KT").grade("KT_GENERAL").context(context)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(first, second), OfferResolution.SCOPED, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 혜택 요약", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("보리보리 혜택 내용을 요약해줘."), detail, List.of(candidate));

        assertThat(answer.reason()).doesNotContain(context).contains("혜택 카드에서 확인");
    }

    @Test
    void simpleAmountQuestionUsesOnlyTypedDiscountFactAndCannotMislabelSpendCap() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        List<BenefitFact> facts = List.of(
                new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null, new BigDecimal("1000"),
                        "KRW", null, null, "1,000원당 50원 할인", null),
                new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "DAY", null,
                        null, null, null, "일 1회", null),
                new BenefitFact("SPEND_CAP", new BigDecimal("200000"), "KRW", null, null,
                        null, null, "MAX", "최대 20만원", null));
        BenefitOffer offer = new BenefitOffer("offline-discount", "이마트24 할인", "", UsageType.OFFLINE,
                List.of("BASIC"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, facts);
        Candidate candidate = Candidate.builder().candidateKey("benefit:11:policy:21:tier:31")
                .partnerName("이마트24").benefitName("멤버십 할인").carrier("LGU").grade("BASIC")
                .usageType("OFFLINE").conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("이마트24 할인 금액", List.of("편의점"), List.of("이마트24"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.BASIC, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("이마트24 할인은 얼마야?"), detail, List.of(candidate));

        assertThat(answer.candidateKeys()).containsExactly(candidate.getCandidateKey());
        assertThat(answer.reason()).isEqualTo("이마트24 LG유플러스 우수 혜택은 50원 할인 (1000원당)입니다. 자세한 이용 조건은 혜택 카드에서 확인해 주세요.")
                .doesNotContain("200000", "20만원", "1회");
        verifyNoInteractions(model);
    }

    @Test
    void amountCalculationQuestionDoesNotInventAComputedDiscountFromABasisAmount() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String reason = "이마트24 LG유플러스 VIP 혜택은 50원 할인입니다.";
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"" + reason
                + "\",\"candidateKeys\":[\"vip\"],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitOffer offer = new BenefitOffer("vip-offer", "이마트24 할인", "", UsageType.OFFLINE,
                List.of("VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, null, "1,000원당 50원 할인", null)));
        Candidate candidate = Candidate.builder().candidateKey("vip").partnerName("이마트24").benefitName("멤버십 할인")
                .carrier("LGU").grade("VIP").usageType("OFFLINE")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("이마트24 할인 금액", List.of("편의점"), List.of("이마트24"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("1만원 결제하면 이마트24 할인은 얼마야?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("이마트24", "50원 할인 (1000원당)")
                .doesNotContain("VIP 혜택은 50원 할인입니다");
        verifyNoInteractions(model);
    }

    @Test
    void deterministicSearchFallbackUsesOnlyPartnerNames() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitChatPlan search = new BenefitChatPlan("편의점 혜택", List.of("편의점"), List.of(), List.of(),
                List.of(), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate c = candidate();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("편의점"), search, List.of(c));

        assertThat(answer.reason()).contains("CGV", "카드에서 자세히 확인")
                .doesNotContain("KT", "VIP", "월 1회", "현장 예매");
    }

    @Test
    void compareSuccessPreservesGroundedDifferenceInsteadOfReplacingItWithNames() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"KT VIP는 월 1회, SKT Gold는 월 2회 이용할 수 있어요.\",\"candidateKeys\":[\"benefit:1:policy:2:tier:3\",\"skt\"],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan compare = new BenefitChatPlan("3사 비교", List.of(), List.of(), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);
        Candidate kt = candidate();
        Candidate skt = Candidate.builder().candidateKey("skt").partnerName("SKT 제휴처").carrier("SKT")
                .grade("SKT_GOLD").sourceLimitText("월 2회").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("통신사별 할인 횟수 비교"), compare, List.of(kt, skt));

        assertThat(answer.candidateKeys()).containsExactly(kt.getCandidateKey(), skt.getCandidateKey());
        assertThat(answer.reason()).contains("CGV", "SKT 제휴처", "KT", "SK텔레콤")
                .doesNotContain("월 1회", "월 2회");
    }

    @Test
    void internalComparisonReasonsUseGroundedIntroAndPreserveSelectedCandidates() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        List<String> reasons = List.of("COMPARE", "DETAIL", "compare-available-offers", "compare-carriers",
                "COMPARE_EVIDENCE_FOUND", "positive-redemption-evidence", "CODE_ONLY_REASON",
                "통신사 비교 결과: COMPARE_EVIDENCE_FOUND", "통신사 비교 결과: DETAIL");
        org.mockito.stubbing.OngoingStubbing<Flux<ChatResponse>> stubbing = when(model.stream(any(Prompt.class)));
        ObjectMapper mapper = new ObjectMapper();
        for (String reason : reasons) {
            stubbing = stubbing.thenReturn(response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                    reason, List.of("kt", "skt"), List.of()))));
        }
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan compare = new BenefitChatPlan("3사 비교", List.of(), List.of(), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);
        Candidate kt = Candidate.builder().candidateKey("kt").partnerName("CGV").benefitName("영화 할인")
                .carrier("KT").grade("KT_VIP").build();
        Candidate skt = Candidate.builder().candidateKey("skt").partnerName("롯데시네마").benefitName("영화 혜택")
                .carrier("SKT").grade("SKT_GOLD").build();

        for (String internalReason : reasons) {
            BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("통신사별 혜택 비교"), compare, List.of(kt, skt));

            assertThat(answer.candidateKeys()).containsExactly("kt", "skt");
            assertThat(answer.reason()).contains("CGV (KT VIP)", "롯데시네마 (SK텔레콤 Gold)")
                    .doesNotContain("COMPARE", "compare-available-offers", "compare-carriers", "positive-redemption-evidence");
        }
    }

    @Test
    void internalReasonFallbackUsesCompactDistinctBrandsAndPreservesAllSelectedKeys() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        List<Candidate> candidates = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> Candidate.builder().candidateKey("key-" + index).partnerName("CGV")
                        .benefitName("영화 혜택 " + index).carrier("KT").grade("KT_VIP").build()).toList();
        List<String> keys = candidates.stream().map(Candidate::getCandidateKey).toList();
        when(model.stream(any(Prompt.class))).thenReturn(response(new ObjectMapper().writeValueAsString(
                new BenefitChatAnswerer.ChatAnswer("상태 DETAIL: CODE_ONLY_REASON", keys, List.of()))));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("CGV 혜택"), plan(), candidates);

        assertThat(answer.candidateKeys()).containsExactlyElementsOf(keys);
        assertThat(answer.reason()).contains("CGV (KT VIP) 혜택", "카드에서 확인")
                .doesNotContain("DETAIL", "CODE_ONLY_REASON", "영화 혜택 1");
        assertThat(answer.reason().length()).isLessThan(100);
    }

    @Test
    void schemaFieldNamesMixedIntoKoreanReasonUseGroundedIntro() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        ObjectMapper mapper = new ObjectMapper();
        String key = "cu";
        when(model.stream(any(Prompt.class))).thenReturn(
                response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                        "CU 혜택은 carrier별로 적립형/할인형 구분과 등급별 차이가 있으며, 일부 혜택은 중복 적용이 제한됩니다.",
                        List.of(key), List.of()))),
                response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                        "grade별 혜택은 usageType 기준으로 달라요.", List.of(key), List.of()))),
                response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                        "각 carriers별로 혜택 차이가 존재합니다.", List.of(key), List.of()))),
                response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                        "grades마다 혜택이 달라요.", List.of(key), List.of()))),
                response(mapper.writeValueAsString(new BenefitChatAnswerer.ChatAnswer(
                        "CU KT VIP 혜택은 확인된 후보예요.", List.of(key), List.of()))));
        BenefitChatAnswerer answerer = createAnswerer(model, mapper, "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan compare = new BenefitChatPlan("CU 통신사별 비교", List.of(), List.of("CU"), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);
        Candidate cu = Candidate.builder().candidateKey(key).partnerName("CU").benefitName("편의점 혜택")
                .carrier("KT").grade("KT_VIP").build();

        for (int i = 0; i < 4; i++) {
            BenefitChatAnswerer.ChatAnswer internalFields = answerer.answer(request("CU 통신사별 비교"), compare, List.of(cu));
            assertThat(internalFields.candidateKeys()).containsExactly(key);
            assertThat(internalFields.reason()).contains("CU (KT VIP) 혜택을 찾았어요.")
                    .doesNotContain("carrier", "grade", "usageType");
        }
        BenefitChatAnswerer.ChatAnswer userFacing = answerer.answer(request("CU 통신사별 비교"), compare, List.of(cu));
        assertThat(userFacing.candidateKeys()).containsExactly(key);
        assertThat(userFacing.reason()).contains("CU (KT VIP) 혜택을 찾았어요.")
                .doesNotContain("확인된 후보예요");
    }

    @Test
    void deterministicAnswerDisplaysDiamondAsKoreanSourceGrade() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        Candidate diamond = Candidate.builder().candidateKey("diamond").partnerName("제휴처")
                .carrier("LGU").grade("DIAMOND").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("LG유플러스 다이아몬드 혜택"), plan(), List.of(diamond));

        assertThat(answer.reason()).contains("다이아몬드").doesNotContain("DIAMOND");
    }

    @Test
    void deterministicCarrierComparisonKeepsOneCandidatePerRepresentedCarrier() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        Candidate kt = candidate();
        Candidate ktSecond = Candidate.builder().candidateKey("kt-second").partnerName("다른 KT 제휴처")
                .carrier("KT").grade("KT_VIP").membershipOptionType("UNKNOWN").build();
        Candidate skt = Candidate.builder().candidateKey("skt").partnerName("SKT 제휴처")
                .carrier("SKT").grade("SKT_GOLD").membershipOptionType("DISCOUNT").build();
        Candidate lgu = Candidate.builder().candidateKey("lgu").partnerName("LGU 제휴처")
                .carrier("LGU").grade("VIP").sourceOptionLabel("현장 할인").build();
        BenefitChatPlan compare = new BenefitChatPlan("3사 비교", List.of(), List.of(), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("3사 비교"), compare,
                List.of(kt, ktSecond, skt, lgu));

        assertThat(answer.candidateKeys()).containsExactly(kt.getCandidateKey(), "skt", "lgu");
        assertThat(answer.reason()).contains("KT", "VIP", "SK텔레콤", "Gold", "LG유플러스")
                .doesNotContain("KT_VIP", "SKT_GOLD", "UNKNOWN");
    }

    @Test
    void acceptsOnlySelectedGroundedEvidenceAndTreatsEmptySelectionAsNoResults() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"CGV KT_VIP 혜택은 월 1회, 현장 예매 조건입니다.\",\"candidateKeys\":[\"benefit:1:policy:2:tier:3\"],\"suggestedQuestions\":[\"이용 조건 자세히 보기\"]}"),
                response("{\"reason\":\"후보가 질문과 맞지 않습니다.\",\"candidateKeys\":[],\"suggestedQuestions\":[\"다른 분야 혜택 보기\"]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        Candidate c = candidate();

        BenefitChatAnswerer.ChatAnswer selected = answerer.answer(request("월 몇 번?"), plan(), List.of(c));
        BenefitChatAnswerer.ChatAnswer noMatch = answerer.answer(request("월 몇 번?"), plan(), List.of(c));

        assertThat(selected.candidateKeys()).containsExactly(c.getCandidateKey());
        assertThat(selected.reason()).contains("혜택 카드에서 확인").doesNotContain("월 1회");
        assertThat(noMatch.candidateKeys()).isEmpty();
        assertThat(noMatch.reason()).contains("찾지 못");
    }

    @Test
    void emptyModelSelectionRecoversOnlyAnExactRequestedPartner() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response(
                "{\"reason\":\"후보가 질문과 맞지 않습니다.\",\"candidateKeys\":[],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatPlan bori = new BenefitChatPlan("보리보리 KT 쿠폰", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT), null, false, "ANY", false, "", "DETAIL", "", false);
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("쿠폰 할인").carrier("KT").grade("KT_VIP").manual("쿠폰은 KT 앱에서 발급").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("보리보리 쿠폰 어디서 발급해?"), bori, List.of(candidate));

        assertThat(answer.candidateKeys()).containsExactly("bori-kt");
        assertThat(answer.reason()).contains("보리보리", "KT").doesNotContain("보리보리 앱에서 발급");
    }

    @Test
    void exactPartnerAnswerUsesGroundedFastPathAndFixedSuggestions() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatPlan bori = new BenefitChatPlan("보리보리 KT 쿠폰", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT), null, false, "ANY", false, "", "DETAIL", "", false);
        Candidate candidate = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리")
                .benefitName("쿠폰 할인").carrier("KT").grade("KT_VIP").manual("쿠폰은 KT 앱에서 발급").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("보리보리 쿠폰 어디서 발급해?"), bori, List.of(candidate));

        assertThat(answer.candidateKeys()).containsExactly("bori-kt");
        assertThat(answer.reason()).contains("보리보리", "KT").doesNotContain("보리보리 앱에서 발급");
        assertThat(answer.suggestedQuestions()).containsExactly("이 혜택의 이용 조건 자세히 보기", "사용 횟수와 제한 확인하기");
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void emptySelectionKeepsUnrelatedCandidateOutEvenWhenCategoryMatches() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response(
                "{\"reason\":\"질문과 다른 혜택입니다.\",\"candidateKeys\":[],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatPlan bori = new BenefitChatPlan("보리보리 쿠폰", List.of("영화"), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT), null, false, "ANY", false, "", "DETAIL", "", false);
        Candidate cgv = Candidate.builder().candidateKey("cgv").partnerName("CGV").category("영화")
                .carrier("KT").grade("KT_VIP").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("보리보리 쿠폰"), bori, List.of(cgv));

        assertThat(answer.candidateKeys()).isEmpty();
        assertThat(answer.reason()).contains("찾지 못");
    }

    @Test
    void categoryRecoveryUsesSharedFoodBusinessTypeMapping() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response(
                "{\"reason\":\"해당 혜택은 없어요.\",\"candidateKeys\":[],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatPlan dining = new BenefitChatPlan("외식 혜택", List.of("외식"), List.of(), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate food = Candidate.builder().candidateKey("food").partnerName("식당").carrier("KT")
                .businessType("FOOD_RESTAURANT").build();
        Candidate cafe = Candidate.builder().candidateKey("cafe").partnerName("카페").carrier("KT")
                .businessType("BEVERAGE_CAFE").build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("외식 혜택"), dining, List.of(cafe, food));

        assertThat(answer.candidateKeys()).containsExactly("food");
        assertThat(answer.reason()).contains("식당").doesNotContain("카페");
    }

    @Test
    void deliveryAnswerKeepsWholeNegativeSourceClauseAndDropsModelParaphrase() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response(
                "{\"reason\":\"굽네 배달앱에서 사용할 수 있어요.\",\"candidateKeys\":[\"goobne\"],\"suggestedQuestions\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatPlan detail = new BenefitChatPlan("굽네 배달앱 이용 조건", List.of("치킨"), List.of("굽네"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU), null, false, "ANY", false, "", "DETAIL", "", false);
        Candidate goobne = Candidate.builder().candidateKey("goobne").partnerName("굽네")
                .carrier("LGU").grade("VIP").conditions(new BenefitConditions(List.of(), List.of(), List.of(),
                        List.of(), List.of("배달앱 사용 불가"))).build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("굽네 배달앱에서 쓸 수 있어?"), detail, List.of(goobne));

        assertThat(answer.reason()).contains("배달앱 사용 불가").doesNotContain("배달앱에서 사용할 수 있어요");
    }

    @Test
    void exclusionQuestionKeepsIntactScopedClauseWithoutNeedingDeliveryKeyword() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitChatPlan detail = new BenefitChatPlan("굽네 혜택 제외 조건", List.of("치킨"), List.of("굽네"),
                List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU), null, false,
                "ANY", false, "", "DETAIL", "", false);
        Candidate goobne = Candidate.builder().candidateKey("goobne").partnerName("굽네")
                .carrier("LGU").grade("VIP").conditions(new BenefitConditions(List.of(), List.of(), List.of(),
                        List.of(), List.of("배달앱 사용 불가"))).build();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("굽네 혜택의 제외 항목이 궁금해."), detail, List.of(goobne));

        assertThat(answer.reason()).contains("배달앱 사용 불가");
    }

    @Test
    void methodQuestionQuotesOnlyTheSelectedOfferUsageClause() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String usage = "쿠폰 번호 발급 후 보리보리 홈페이지/앱에서 등록해 결제 시 적용";
        BenefitOffer offer = new BenefitOffer("bori-kt", "보리보리 KT 멤버십", "", UsageType.ONLINE,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of(), List.of(usage), List.of(), List.of(), null,
                List.of());
        Candidate bori = Candidate.builder().candidateKey("bori-kt").partnerName("보리보리").carrier("KT")
                .grade("KT_GENERAL").conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("보리보리 사용 방법", List.of(), List.of("보리보리"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("보리보리 멤버십 혜택 사용 방법은?"), detail, List.of(bori));

        assertThat(answer.reason()).contains(usage);
    }

    @Test
    void detailFactsPreserveMinimumSpendAndTotalUsagePeriod() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer offer = new BenefitOffer("movie", "영화 혜택", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("MIN_SPEND", new BigDecimal("20000"), "KRW", null, null,
                                null, null, null, "2만원 이상 결제", null),
                        new BenefitFact("USAGE_LIMIT", new BigDecimal("3"), "COUNT", "TOTAL", null,
                                null, null, null, "총 3회", null)));
        Candidate candidate = Candidate.builder().candidateKey("kt-movie").partnerName("CGV")
                .carrier("KT").grade("KT_VIP").conditions(new BenefitConditions(List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("CGV 이용 조건", List.of("영화"), List.of("CGV"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("최소 결제 금액과 총 이용 횟수 알려줘"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("20000원 이상 결제", "3회/총").doesNotContain("/월");
    }

    @Test
    void carrierComparisonKeepsMonthlyAndYearlyFactsWithTheirOwnCarriers() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        BenefitOffer ktOffer = new BenefitOffer("kt", "혜택", "", UsageType.OFFLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "MONTH", null,
                        null, null, null, "월 1회", null)));
        BenefitOffer sktOffer = new BenefitOffer("skt", "혜택", "", UsageType.OFFLINE,
                List.of("SKT_GOLD"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", new BigDecimal("2"), "COUNT", "YEAR", null,
                        null, null, null, "연 2회", null)));
        Candidate kt = Candidate.builder().candidateKey("kt-count").partnerName("CGV").carrier("KT").grade("KT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(ktOffer), OfferResolution.SINGLE, null)).build();
        Candidate skt = Candidate.builder().candidateKey("skt-count").partnerName("CGV").carrier("SKT").grade("SKT_GOLD")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(sktOffer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan compare = new BenefitChatPlan("CGV 통신사별 횟수 비교", List.of("영화"), List.of("CGV"), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("월·연 이용 횟수 비교"), compare, List.of(kt, skt));

        assertThat(answer.reason()).contains("CGV KT VIP은 1회/월", "CGV SK텔레콤 Gold은 2회/년")
                .doesNotContain("1회/년", "2회/월");
    }

    @Test
    void timeoutFallsBackWithoutLeakingAnUnsupportedClaim() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofMillis(20), true);
        Candidate c = candidate();

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("월 99회 가능해?"), plan(), List.of(c));

        assertThat(answer.candidateKeys()).containsExactly(c.getCandidateKey());
        assertThat(answer.reason()).contains("혜택 카드에서 확인").doesNotContain("99회", "월 1회");
    }

    @Test
    void providerCancellationPropagatesInsteadOfFallingBack() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new java.util.concurrent.CancellationException("cancelled")));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);

        assertThatThrownBy(() -> answerer.answer(request("월 몇 번?"), plan(), List.of(candidate())))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void onlyFollowUpPromptsIncludePriorUserMessagesAndSystemForbidsUnsupportedNearbyClaims() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String response = "{\"reason\":\"확인된 혜택입니다.\",\"candidateKeys\":[\"benefit:1:policy:2:tier:3\"],\"suggestedQuestions\":[]}";
        when(model.stream(any(Prompt.class))).thenReturn(response(response), response(response));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-4o-mini", Duration.ofSeconds(1), true);
        BenefitChatRequest request = new BenefitChatRequest("편의점", List.of(new ChatMessage("user", "우리집 근처에서 인기있는 혜택")),
                List.of(), null, null, null, null);
        BenefitChatPlan freshTopic = new BenefitChatPlan("편의점 혜택 편의점", List.of("편의점"), List.of(), List.of(),
                List.of(), null, false, "ANY", false, "", "SEARCH", "", false);
        BenefitChatPlan followUp = new BenefitChatPlan("편의점 이용 조건", List.of("편의점"), List.of(), List.of(),
                List.of(), null, false, "ANY", false, "", "DETAIL", "", true);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);

        answerer.answer(request, freshTopic, List.of(candidate()));
        answerer.answer(request, followUp, List.of(candidate()));

        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).stream(prompts.capture());
        var freshPayload = new ObjectMapper().readTree(prompts.getAllValues().get(0).getUserMessage().getText());
        var followUpPayload = new ObjectMapper().readTree(prompts.getAllValues().get(1).getUserMessage().getText());
        assertThat(freshPayload.path("question").asText()).isEqualTo("편의점");
        assertThat(freshPayload.path("plan").path("query").asText()).isEqualTo("편의점 혜택 편의점");
        assertThat(freshPayload.path("messages")).isEmpty();
        assertThat(followUpPayload.path("messages")).hasSize(1);
        assertThat(followUpPayload.path("messages").get(0).path("content").asText()).contains("근처에서 인기");
        assertThat(prompts.getAllValues().get(0).getSystemMessage().getText())
                .contains("위치/거리", "인기도", "후보 근거에 위치·거리·인기도 자료가 없으면",
                        "SINGLE의 공통 manual/sourceText", "allGrades는 혜택 자격만", "SCOPED는 선택 후보에 포함된 매칭 offer",
                        "사용자에게 그대로 보여 줄 자연스러운 한국어 답변", "COMPARE_EVIDENCE_FOUND", "carrier, grade, usageType",
                        "통신사, 등급, 이용 채널");
    }

    @Test
    void multipleSamePartnerCandidatesStillShowTwoConciseFactsWhenMoreThanThreeAreSelected() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String onlineKey = "offer-c69fb6c072b436f9";
        String offlineKey = "offer-d90dc5c4c0e67c31";
        String vipChoiceKey = "offer-84affbc11433127d";
        String birthdayKey = "offer-4d8f07ff80daedc4";
        String onlineDiscount = "- 혜택: 최대 5,000원 할인 (KT멤버십 앱/웹 예매 시)";
        String offlineDiscount = "- 혜택: 롯데시네마 현장 매점 콤보 3,000원 할인";
        BenefitOffer online = new BenefitOffer(onlineKey, "상시 할인", "", UsageType.ONLINE,
                List.of(), true, List.of(), List.of(), List.of(onlineDiscount), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("5000"), "KRW", null, null, null, null,
                        "MAX", onlineDiscount, null)));
        BenefitOffer offline = new BenefitOffer(offlineKey, "현장 할인 혜택", "", UsageType.OFFLINE,
                List.of(), true, List.of(), List.of(), List.of("▶ 대상: KT멤버십 전등급\n- 이용횟수: 월 4회"),
                List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("3000"), "KRW", null, null, null, null,
                        "EXACT", offlineDiscount, null)));
        BenefitOffer vipChoice = new BenefitOffer(vipChoiceKey, "V/VIP초이스 — VVIP", "", UsageType.ONLINE,
                List.of("KT_VVIP"), false, List.of(), List.of(),
                List.of("▶ 대상: VVIP/VIP\n- 이용횟수: VVIP/VIP초이스 통합 월 1회",
                        "▶ 대상: VVIP/VIP\n- 추가혜택: 무료예매 시, 동반 4인까지 인당 최소 1,000원~최대 5,000원 할인",
                        "▶ 대상: VVIP/VIP\n※ 동반할인은 주중 11,000원 이상, 주말 12,000원 이상의 티켓부터 적용 가능"),
                List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "MONTH", null, null, null,
                        "EXACT", "- 이용횟수: VVIP/VIP초이스 통합 월 1회", null)));
        BenefitOffer birthday = new BenefitOffer(birthdayKey, "VVIP초이스 생일혜택", "", UsageType.UNKNOWN,
                List.of("KT_VVIP"), false, List.of(), List.of(), List.of("▶ 대상: VVIP등급\n- 이용횟수: VVIP초이스 통합 월 1회"),
                List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "MONTH", null, null, null,
                        "EXACT", "- 이용횟수: VVIP초이스 통합 월 1회", null)));
        List<Candidate> candidates = List.of(
                linkedPartialCandidate("lotte-online", "롯데시네마", "롯데시네마 KT 멤버십", "KT", "KT_VVIP", online),
                linkedPartialCandidate("lotte-offline", "롯데시네마", "롯데시네마 KT 멤버십", "KT", "KT_VVIP", offline),
                linkedPartialCandidate("lotte-vip-choice", "롯데시네마", "롯데시네마 KT 멤버십", "KT", "KT_VVIP", vipChoice),
                linkedPartialCandidate("lotte-birthday", "롯데시네마", "롯데시네마 KT 멤버십", "KT", "KT_VVIP", birthday));
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"롯데시네마 할인\",\"candidateKeys\":[\"lotte-online\"],\"suggestedQuestions\":[],\"clauseIds\":[\"clause-1\"]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan detail = new BenefitChatPlan("롯데시네마 할인액", List.of("영화"), List.of("롯데시네마"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("롯데시네마 할인액이 얼마야?"), detail, candidates);

        assertThat(answer.candidateKeys()).containsExactly("lotte-online");
        assertThat(answer.reason()).contains("5,000원 할인")
                .doesNotContain("정확한 조건은 혜택 카드");
        assertThat(answer.reason().length()).isLessThan(300);
    }

    @Test
    void explicitDiscountQuestionDoesNotTreatUseCountsAsAmounts() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String discountClause = "VIP/GOLD/SILVER: 3% 할인 (5만 원권/3만 원권/1만 원권)";
        String sharedLimit = "전 등급 일 1회 총 4매 할인 포인트 사용 불가";
        String source = "[할인형]\n" + discountClause + "\n※ 유의사항\n" + sharedLimit;
        BenefitOffer discountOffer = new BenefitOffer("offer-d532c77f53ffcc83", "할인형", source, UsageType.UNKNOWN,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), true,
                List.of("컬쳐랜드상품권은 컬쳐랜드 상품권이 사용 가능한 오프라인 사용처 및 컬쳐랜드에 등록한 뒤 사용하실 수 있습니다."),
                List.of(), List.of(sharedLimit), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer rewardOffer = new BenefitOffer("offer-3f9bf4e52dae3d78", "적립형", source, UsageType.UNKNOWN,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), true,
                List.of("컬쳐랜드상품권은 컬쳐랜드 상품권이 사용 가능한 오프라인 사용처 및 컬쳐랜드에 등록한 뒤 사용하실 수 있습니다."),
                List.of(), List.of(sharedLimit), List.of(), List.of(), List.of(), null, List.of());
        Candidate discount = linkedPartialCandidate("culture-discount", "컬쳐랜드", "컬쳐랜드 할인형", "SKT", "SKT_VIP", discountOffer);
        Candidate reward = linkedPartialCandidate("culture-reward", "컬쳐랜드", "컬쳐랜드 적립형", "SKT", "SKT_VIP", rewardOffer);
        BenefitChatPlan detail = new BenefitChatPlan("컬쳐랜드 할인 조건", List.of(), List.of("컬쳐랜드"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("컬쳐랜드 할인형은 포인트 적립인지, 금액 할인인지, 숫자 조건은 어때?"), detail, List.of(discount, reward));

        assertThat(answer.reason()).doesNotContain("3% 할인", "1회", "4매", "포인트 사용 불가")
                .contains("혜택 카드에서 확인");
    }

    @Test
    void genericNamedPartnerSummaryPrefersPrimaryProductClauseOverSecondaryLimit() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String key = "offer-ea55b130893fd975";
        String primary = "네이버플러스 멤버십 1개월 무료 이용권 제공";
        String secondary = "- 네이버에서 쇼핑할 때 NPay+ 마크가 붙은 상품은 최대 5% 네이버페이 포인트가 적립됩니다.";
        BenefitOffer offer = new BenefitOffer(key, "네이버플러스 멤버십 VIP콕",
                primary + "\nVIP콕 통합 월 1회\n" + secondary, UsageType.UNKNOWN,
                List.of("VVIP", "VIP"), false,
                List.of(primary, "② 멤버십 이용권 받기 > 생년월일 입력 후 등록 완료"), List.of(),
                List.of("VIP콕 통합 월 1회", secondary), List.of(), List.of(), List.of(), null, List.of());
        Candidate candidate = linkedPartialCandidate("benefit:916:policy:1055:offer:" + key,
                "네이버플러스 멤버십", "네이버플러스 멤버십 VIP콕", "LGU", "VIP", offer);
        BenefitChatPlan detail = new BenefitChatPlan("네이버플러스 멤버십 요약", List.of(), List.of("네이버플러스 멤버십"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU), com.itplace.userapi.benefit.entity.enums.Grade.VIP,
                false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("네이버플러스 멤버십에서 쓸 수 있는 LG유플러스 멤버십 혜택만 알려줘."), detail, List.of(candidate));

        assertThat(answer.reason()).contains(primary).doesNotContain("NPay+", "월 1회");
        verifyNoInteractions(model);
    }

    @Test
    void detailWithNoRelevantAllowedClauseReturnsHonestFixedAnswerAndCards() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String product = "멤버십 1개월 이용권 제공";
        BenefitOffer offer = new BenefitOffer("offer-a", "제휴 혜택", "", UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(product), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        Candidate candidate = linkedPartialCandidate("candidate-a", "제휴처", "제휴 혜택", "SKT", "SKT_VIP", offer);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"\",\"candidateKeys\":[\"candidate-a\"],\"suggestedQuestions\":[],\"clauseIds\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan detail = new BenefitChatPlan("제휴처 최소결제 조건", List.of(), List.of("제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("제휴처 최소 결제 금액이 얼마야?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("확인 가능한 원문 근거에서 찾지 못했어요", "혜택 카드에서 확인")
                .doesNotContain(product);
        assertThat(answer.candidateKeys()).containsExactly("candidate-a");
    }

    @Test
    void samePartnerFallbackLabelsDistinctOffersWithTitleAndChannel() {
        BenefitOffer onlineOffer = new BenefitOffer("offer-online", "상시 할인", "", UsageType.ONLINE,
                List.of("KT_VVIP"), false, List.of(), List.of(), List.of("최대 5,000원 할인"), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer offlineOffer = new BenefitOffer("offer-offline", "현장 할인 혜택", "", UsageType.OFFLINE,
                List.of("KT_VVIP"), false, List.of(), List.of(), List.of("3,000원 할인"), List.of(), List.of(), List.of(), null, List.of());
        Candidate online = Candidate.builder().candidateKey("lotte-online").offerKey("offer-online").partnerName("롯데시네마")
                .benefitName("롯데시네마 KT 멤버십").carrier("KT").grade("KT_VVIP").usageType("ONLINE")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(onlineOffer), OfferResolution.PARTIAL, null)).build();
        Candidate offline = Candidate.builder().candidateKey("lotte-offline").offerKey("offer-offline").partnerName("롯데시네마")
                .benefitName("롯데시네마 KT 멤버십").carrier("KT").grade("KT_VVIP").usageType("OFFLINE")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(offlineOffer), OfferResolution.PARTIAL, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("롯데시네마 할인액", List.of(), List.of("롯데시네마"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_VVIP, false, "ANY", false, "", "DETAIL", "", false);
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("롯데시네마 혜택의 할인 금액을 알려줘."), detail, List.of(online, offline));

        assertThat(answer.reason()).contains("상시 할인", "온라인", "5,000원", "현장 할인 혜택", "오프라인", "3,000원");
    }

    @Test
    void samePartnerFallbackKeepsBirthdayOfferConditionsSeparateFromRegularOffer() {
        BenefitOffer regularOffer = new BenefitOffer("offer-regular", "상시", "", UsageType.UNKNOWN,
                List.of("KT_GENERAL"), true, List.of(), List.of(), List.of("월 1회"), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("USAGE_LIMIT", BigDecimal.ONE, "COUNT", "MONTH", null, null, null,
                        "EXACT", "월 1회", null)));
        BenefitOffer birthdayOffer = new BenefitOffer("offer-birthday", "생일혜택", "", UsageType.UNKNOWN,
                List.of("KT_GENERAL"), true, List.of(), List.of(),
                List.of("TV 방송상품 5만원 이상 결제 시 이용 가능", "KT 알파쇼핑 ID 기준 1년에 1회 사용 가능"), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("10000"), "KRW", null, null, null, null,
                                "EXACT", "1만원 할인", null),
                        new BenefitFact("MIN_SPEND", new BigDecimal("50000"), "KRW", null, null, null, null,
                                "MIN", "TV 방송상품 5만원 이상 결제 시 이용 가능", null)));
        Candidate regular = linkedPartialCandidate("kshop-regular", "KT알파 쇼핑", "KT알파 쇼핑 KT 멤버십", "KT", "KT_GENERAL", regularOffer);
        Candidate birthday = linkedPartialCandidate("kshop-birthday", "KT알파 쇼핑", "KT알파 쇼핑 KT 멤버십", "KT", "KT_GENERAL", birthdayOffer);
        BenefitChatPlan detail = new BenefitChatPlan("KT알파 쇼핑 할인 내용", List.of(), List.of("KT알파 쇼핑"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.KT),
                com.itplace.userapi.benefit.entity.enums.Grade.KT_GENERAL, false, "ANY", false, "", "DETAIL", "", false);
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("KT알파 쇼핑 할인 혜택은 얼마고 어떤 조건이야?"), detail, List.of(regular, birthday));

        assertThat(answer.reason()).contains("생일혜택", "10000원 할인")
                .doesNotContain("월 1회, 연 1회", "1회/월, 1회/년");
        assertThat(answer.reason().indexOf("생일혜택")).isLessThan(answer.reason().indexOf("10000원 할인"));
    }

    @Test
    void perSpendDiscountQuestionQuotesRateConditionsWithoutCalculating() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String key = "offer-8522d491376680e0";
        String source = "VIP/GOLD/SILVER: 10% 할인(50,000원이상 구매 시 최대 5,000원 할인)";
        BenefitOffer discountOffer = new BenefitOffer(key, "할인형", "[할인형]\n" + source, UsageType.ONLINE,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false, List.of(), List.of(),
                List.of(source, "쿠폰은 본인 명의의 T 멤버십 카드당 월 1회 다운로드 하실 수 있습니다."),
                List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer rewardOffer = new BenefitOffer("offer-397443b9240f4187", "적립형", "[적립형]\n" + source,
                UsageType.ONLINE, List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false,
                List.of(), List.of(), List.of(source, "쿠폰은 본인 명의의 T 멤버십 카드당 월 1회 다운로드 하실 수 있습니다."),
                List.of(), List.of(), List.of(), null, List.of());
        Candidate discount = linkedPartialCandidate("benefit:475:policy:639:offer:" + key,
                "키디키디", "키디키디 몰 할인형", "SKT", "SKT_VIP", discountOffer);
        Candidate reward = linkedPartialCandidate("benefit:476:policy:248:offer:offer-397443b9240f4187",
                "키디키디", "키디키디 몰 적립형", "SKT", "SKT_VIP", rewardOffer);
        BenefitChatPlan detail = new BenefitChatPlan("키디키디 할인 조건", List.of(), List.of("키디키디"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("키디키디 멤버십 혜택은 1,000원당 얼마를 깎아 주는 조건이야?"), detail, List.of(discount, reward));

        assertThat(answer.reason()).contains(source).doesNotContain("1,000원당", "100원 할인", "월 1회")
                .contains("적립형 · 적립형: “" + source + "”");
    }

    @Test
    void accumulationOfferDoesNotReuseMisclassifiedDiscountCap() {
        String discountSource = "최대 할인 가능: 결제 건당 최대 17,400원";
        String pointsSource = "최대 적립 가능: 결제 건당 최대 17,400P";
        String usageSource = "횟수 제한: 1일 1회 할인 또는 적립";
        BenefitFact usage = new BenefitFact("USAGE_LIMIT", BigDecimal.ONE, "COUNT", "DAY", null, null, null,
                "EXACT", usageSource, null);
        BenefitFact cap = new BenefitFact("DISCOUNT_CAP", new BigDecimal("17400"), "KRW", null, null, null, null,
                "MAX", discountSource, null);
        String ownedPath = "detail:div.detail-list > dl > dd > dl.dl-bnf:nth-of-type(2)/dd:nth-of-type(1)/div.info:nth-of-type(1)";
        String ownedReward = "VIP/GOLD/SILVER: 전망대 입장권 20% 적립(본인 외 동반 2인)";
        BenefitOffer discountOffer = new BenefitOffer("offer-discount", "할인형 — VIP/Gold/Silver", "",
                UsageType.OFFLINE, List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false,
                List.of(), List.of(), List.of(usageSource, discountSource, pointsSource), List.of(), List.of(), List.of(), null,
                List.of(usage, cap));
        BenefitOffer accumulationOffer = new BenefitOffer("offer-accumulation", "적립형 — VIP/Gold/Silver", "",
                UsageType.OFFLINE, List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false,
                List.of(discountSource), List.of(), List.of(usageSource, discountSource, pointsSource), List.of(), List.of(), List.of(), null,
                List.of(usage, cap));
        accumulationOffer = new BenefitOffer(accumulationOffer.key(), accumulationOffer.title(), accumulationOffer.sourceText(),
                accumulationOffer.usageType(), accumulationOffer.gradeCodes(), accumulationOffer.allGrades(),
                accumulationOffer.products(), accumulationOffer.availability(), accumulationOffer.limits(),
                accumulationOffer.usage(), accumulationOffer.exclusions(), accumulationOffer.channelEvidence(),
                ownedPath + "/segment[1]/scope:fixture", accumulationOffer.facts());
        Candidate discount = linkedPartialCandidate("sky-discount", "부산엑스더스카이", "부산엑스더스카이 할인형",
                "SKT", "SKT_VIP", discountOffer).toBuilder()
                .membershipOptionType("DISCOUNT").description("전망대 입장권 20% 할인(본인 외 동반 2인)").build();
        Candidate accumulation = linkedPartialCandidate("sky-accumulation", "부산엑스더스카이", "부산엑스더스카이 적립형",
                "SKT", "SKT_VIP", accumulationOffer).toBuilder()
                .membershipOptionType("POINT_ACCRUAL").description("")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(accumulationOffer), OfferResolution.PARTIAL, null,
                        List.of(new com.itplace.userapi.benefit.dto.BenefitSourceBlock(ownedPath,
                                "detail:div.detail-list > dl > dd > dl.dl-bnf:nth-of-type(2)/dd:nth-of-type(1)",
                                "grade_reward", "적립형", ownedReward)), List.of()))
                .build();
        BenefitChatPlan detail = new BenefitChatPlan("부산엑스더스카이 SKT VIP 혜택", List.of(),
                List.of("부산엑스더스카이"), List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("부산엑스더스카이 멤버십 혜택 중 SK텔레콤 VIP가 받을 수 있는 항목은 뭐야?"),
                detail, List.of(discount, accumulation));

        assertThat(answer.reason()).contains("할인형", "17400원 할인 한도", "적립형", usageSource)
                .doesNotContain("적립형 — VIP/Gold/Silver: 1회/일, 최대 17400원 할인 한도", "17400포인트 적립");
    }

    @Test
    void pointAccrualLabelDoesNotSuppressAnExplicitPrimaryDiscount() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String source = "VIP/GOLD/SILVER: 전통문화코스 25% 할인 쿠폰 제공";
        BenefitFact rate = new BenefitFact("DISCOUNT_RATE", new BigDecimal("25"), "PERCENT", null, null,
                null, null, "EXACT", source, null);
        String ownedPath = "detail/option/1";
        BenefitOffer offer = new BenefitOffer("citybus", "적립형", source, UsageType.ONLINE,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), ownedPath, List.of(rate));
        Candidate candidate = Candidate.builder().candidateKey("citybus-reward").partnerName("노랑풍선 시티버스")
                .benefitName("노랑풍선 시티버스 적립형").membershipOptionType("POINT_ACCRUAL")
                .description("포인트 적립 혜택").carrier("SKT").grade("SKT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null,
                        List.of(new com.itplace.userapi.benefit.dto.BenefitSourceBlock(
                                ownedPath, "detail", "grade_reward", "적립형", source)), List.of())).build();
        BenefitChatPlan detail = new BenefitChatPlan("노랑풍선 할인율", List.of(), List.of("노랑풍선 시티버스"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("노랑풍선 시티버스 적립형의 할인율은 몇 퍼센트야?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("25% 할인");
    }

    @Test
    void negativeDiscountTextDoesNotOpenAccumulationFactGuard() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String source = "VIP: 10% 할인 불가";
        BenefitFact rate = new BenefitFact("DISCOUNT_RATE", new BigDecimal("10"), "PERCENT", null, null,
                null, null, "EXACT", source, null);
        BenefitOffer offer = new BenefitOffer("acc-only", "적립형", source, UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(rate));
        Candidate candidate = Candidate.builder().candidateKey("acc-only-key").partnerName("테스트 제휴처")
                .benefitName("테스트 제휴처 적립형").membershipOptionType("POINT_ACCRUAL")
                .description(source).carrier("SKT").grade("SKT_VIP")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(offer), OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("테스트 제휴처 할인율", List.of(), List.of("테스트 제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("테스트 제휴처 적립형 할인율은 몇 퍼센트야?"), detail, List.of(candidate));

        assertThat(answer.reason()).doesNotContain("10% 할인");
    }

    @Test
    void accumulationOfferKeepsOwnedDiscountWrittenWithSpacedKoreanAmountUnit() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String accrual = "VIP/GOLD/SILVER: 공임, 부가상품 10% 적립";
        String discount = "VIP/GOLD/SILVER: 국산차 엔진오일 2만 원 할인";
        String rewardPath = "detail:div.detail-list > dl:nth-of-type(1)/dd/div.info:nth-of-type(1)";
        String discountPath = "detail:div.detail-list > dl:nth-of-type(1)/dd/div.info:nth-of-type(2)";
        BenefitFact rate = new BenefitFact("REWARD_RATE", new BigDecimal("10"), "PERCENT", null, null,
                null, null, "EXACT", accrual, null);
        BenefitFact amount = new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("20000"), "KRW", null, null,
                null, null, "EXACT", discount, null);
        BenefitOffer offer = new BenefitOffer("speedmate-accrual", "적립형 — VIP/Gold/Silver",
                "[적립형 — VIP/Gold/Silver]\n" + accrual + "\n" + discount, UsageType.OFFLINE,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), rewardPath + "/segment[1]/scope:fixture", List.of(rate, amount));
        Candidate candidate = Candidate.builder().candidateKey("speedmate-623").offerKey(offer.key())
                .partnerName("스피드메이트").benefitName("스피드메이트 적립형").membershipOptionType("POINT_ACCRUAL")
                .sourceOptionLabel("적립형").carrier("SKT").grade("SKT_VIP").usageType("OFFLINE")
                .description(accrual + "\n" + discount)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(offer),
                        OfferResolution.PARTIAL, null, List.of(
                        new com.itplace.userapi.benefit.dto.BenefitSourceBlock(rewardPath, "detail:div.detail-list > dl:nth-of-type(1)/dd", "grade_reward", "적립형", accrual),
                        new com.itplace.userapi.benefit.dto.BenefitSourceBlock(discountPath, "detail:div.detail-list > dl:nth-of-type(1)/dd", "grade_reward", "적립형", discount)), List.of()))
                .build();
        BenefitChatPlan detail = new BenefitChatPlan("스피드메이트 엔진오일 할인", List.of(), List.of("스피드메이트"),
                List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("스피드메이트 SKT 적립형에서도 엔진오일 할인을 받을 수 있어?"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("“" + discount + "”", "국산차 엔진오일", "2만 원 할인")
                .doesNotContain("10% 적립");
    }

    @Test
    void negativeReceiptQuestionUsesScopedExclusionInsteadOfQuotaSummary() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String exclusion = "타 쿠폰 및 혜택 프로그램과 중복 적용 불가";
        String usage = "VIP/Gold/Silver: 1일 1회, 최대 17,400원 할인 한도";
        BenefitFact quota = new BenefitFact("USAGE_LIMIT", BigDecimal.ONE, "COUNT", "DAY", null, null, null,
                "EXACT", "횟수 제한: 1일 1회", null);
        BenefitFact cap = new BenefitFact("DISCOUNT_CAP", new BigDecimal("17400"), "KRW", null, null, null, null,
                "MAX", "최대 할인 가능: 결제 건당 최대 17,400원", null);
        BenefitOffer offer = new BenefitOffer("sky-discount", "할인형 — VIP/Gold/Silver", usage, UsageType.OFFLINE,
                List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER"), false, List.of(), List.of(), List.of(usage),
                List.of(), List.of(exclusion), List.of(), "detail:option/segment[1]/scope:fixture", List.of(quota, cap));
        Candidate candidate = Candidate.builder().candidateKey("sky-164").offerKey(offer.key())
                .partnerName("부산엑스더스카이").benefitName("부산엑스더스카이 할인형")
                .carrier("SKT").grade("SKT_VIP").usageType("OFFLINE")
                .context("VIP/Gold/Silver: 1일 1회, 최대 17,400원 할인 한도")
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(offer),
                        OfferResolution.SINGLE, null)).build();
        BenefitChatPlan detail = new BenefitChatPlan("부산엑스더스카이 미적용 조건", List.of(),
                List.of("부산엑스더스카이"), List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        for (String question : List.of("부산엑스더스카이 제휴 혜택을 받지 못하는 경우가 어떤 건지 알려줘.",
                "부산엑스더스카이 제휴 할인을 받지 못하는 경우가 어떤 건지 알려줘.")) {
            BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request(question), detail, List.of(candidate));
            assertThat(answer.reason()).contains(exclusion).doesNotContain("1회/일", "17400원");
        }

        BenefitChatAnswerer.ChatAnswer count = answerer.answer(
                request("부산엑스더스카이 이용 횟수 제한은 몇 회인가요?"), detail, List.of(candidate));
        assertThat(count.reason()).contains("1회/일");
    }

    @Test
    void searchModeMultiCandidateFallbackQuotesOnlyScopedExclusions() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(response(
                "{\"reason\":\"no clause selected\",\"candidateKeys\":[],\"suggestedQuestions\":[],\"clauseIds\":[]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        String partner = "부산엑스더스카이";
        String exclusion = "타 쿠폰 및 혜택 프로그램과 중복 적용 불가";
        List<String> grades = List.of("SKT_VIP", "SKT_GOLD", "SKT_SILVER");
        BenefitOffer discount = new BenefitOffer("sky-discount", "할인형 — VIP/Gold/Silver",
                "VIP/GOLD/SILVER: 전망대 입장권 20% 할인", UsageType.OFFLINE, grades, false,
                List.of(), List.of(), List.of(), List.of(), List.of(exclusion), List.of(), null, List.of());
        BenefitOffer discountPoints = new BenefitOffer("sky-discount-points", "할인형 — 포인트 사용", "",
                UsageType.OFFLINE, grades, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer accrual = new BenefitOffer("sky-accrual", "적립형 — VIP/Gold/Silver",
                "VIP/GOLD/SILVER: 전망대 입장권 20% 적립", UsageType.OFFLINE, grades, false,
                List.of(), List.of(), List.of(), List.of(), List.of(exclusion), List.of(), null, List.of());
        BenefitOffer accrualPoints = new BenefitOffer("sky-accrual-points", "적립형 — 포인트 사용", "",
                UsageType.UNKNOWN, grades, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        List<Candidate> candidates = List.of(
                partialOfferCandidate("sky-164-discount", partner, partner + " 할인형", "SKT_VIP", discount),
                partialOfferCandidate("sky-164-points", partner, partner + " 할인형", "SKT_VIP", discountPoints),
                partialOfferCandidate("sky-165-accrual", partner, partner + " 적립형", "SKT_VIP", accrual),
                partialOfferCandidate("sky-165-points", partner, partner + " 적립형", "SKT_VIP", accrualPoints));
        BenefitChatPlan search = new BenefitChatPlan("부산엑스더스카이 제휴 혜택을 받지 못하는 경우",
                List.of("관광지"), List.of(partner), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "SEARCH", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("부산엑스더스카이 제휴 혜택을 받지 못하는 경우가 어떤 건지 알려줘."), search, candidates);

        assertThat(answer.candidateKeys()).containsExactlyElementsOf(candidates.stream().map(Candidate::getCandidateKey).toList());
        assertThat(answer.reason()).contains(exclusion).doesNotContain("혜택 카드에서 확인해 주세요.");
    }

    @Test
    void searchModeMultiCandidateExclusionFallbackRejectsWrongGradeOffer() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String partner = "부산엑스더스카이";
        String wrongGradeExclusion = "VIP: 다른 등급 쿠폰 중복 적용 불가";
        BenefitOffer wrongGrade = new BenefitOffer("wrong-grade", "할인형 — VIP/Gold/Silver", "",
                UsageType.OFFLINE, List.of("SKT_GOLD"), false, List.of(), List.of(), List.of(), List.of(),
                List.of(wrongGradeExclusion), List.of(), null, List.of());
        BenefitOffer noExclusion = new BenefitOffer("no-exclusion", "할인형 — 포인트 사용", "",
                UsageType.OFFLINE, List.of("SKT_VIP"), false, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of());
        List<Candidate> candidates = List.of(
                partialOfferCandidate("sky-wrong-grade", partner, partner + " 할인형", "SKT_VIP", wrongGrade),
                partialOfferCandidate("sky-no-exclusion", partner, partner + " 할인형", "SKT_VIP", noExclusion));
        BenefitChatPlan search = new BenefitChatPlan("부산엑스더스카이 제휴 혜택 제한", List.of("관광지"),
                List.of(partner), List.of(), List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "SEARCH", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("부산엑스더스카이 제휴 혜택을 받지 못하는 경우가 어떤 건지 알려줘."), search, candidates);

        assertThat(answer.reason()).doesNotContain(wrongGradeExclusion);
    }

    @Test
    void discountAndAdditionalLimitsIncludeMinimumSpendWhenAvailable() {
        BenefitChatAnswerer answerer = createAnswerer(null, new ObjectMapper(), "", Duration.ofSeconds(1), false);
        String key = "offer-a74efd8b4d0b3f0b";
        String discountClause = "15% 할인(1만원 이상 결제 시)";
        BenefitOffer offer = new BenefitOffer(key, "인천공항 고메브릿지 푸드코트 LGU+ 멤버십",
                discountClause + "\n월 1회", UsageType.OFFLINE, List.of("VVIP", "VIP", "BASIC"), false,
                List.of(), List.of(), List.of(discountClause, "월 1회"), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_RATE", new BigDecimal("15"), "PERCENT", null, null, null, null,
                                "EXACT", discountClause, null),
                        new BenefitFact("MIN_SPEND", new BigDecimal("10000"), "KRW", null, null, null, null,
                                "MIN", discountClause, null),
                        new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "MONTH", null, null, null,
                                "EXACT", "월 1회", null)));
        Candidate candidate = linkedPartialCandidate("benefit:909:policy:1048:offer:" + key,
                "인천공항 고메브릿지 푸드코트", "인천공항 고메브릿지 푸드코트 LGU+ 멤버십", "LGU", "VVIP", offer);
        BenefitChatPlan detail = new BenefitChatPlan("인천공항 고메브릿지 푸드코트 할인 조건", List.of("푸드"),
                List.of("인천공항 고메브릿지 푸드코트"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.VVIP, false, "OFFLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("인천공항 고메브릿지 푸드코트 혜택이 몇 퍼센트 할인인지, 추가 제한도 있는지 알려줘."), detail, List.of(candidate));

        assertThat(answer.reason()).contains("15% 할인", "10000원 이상 결제", "1회/월");
    }

    @Test
    void partialCineQClauseIdsKeepReserveAndGeneralHallAmountsWithTheirOwnOffers() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String regular = "- 일반관/리클라이너관은 1일 1회 최대 8매까지 9천원에 예매 가능합니다.(정상가 대비 최대 6천원 할인)";
        String reserve = "- 리저브관은 1일 1회 최대 2매까지 1만2천원에 예매 가능합니다.(정상가 대비 최대 4천원 할인)";
        String shared = "- 1일 횟수 초과 시 추가 예매 불가합니다.(※예시 : 일반관 4매 예매 후, 남은 4매 추가 예매 불가)";
        List<String> limits = List.of("일 1회", regular, reserve, shared);
        BenefitOffer reserveOffer = new BenefitOffer("offer-886883d67dd052b0", "씨네Q(리저브관) LGU+ 멤버십", "",
                UsageType.ONLINE, List.of("VVIP", "VIP", "BASIC"), false, List.of(), List.of(), limits,
                List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("6000"), "KRW", null, null, null, null,
                        "MAX", regular, null)));
        BenefitOffer regularOffer = new BenefitOffer("offer-bf73cbfcecc05137", "씨네Q(일반관/리클라이너관) LGU+ 멤버십", "",
                UsageType.ONLINE, List.of("VVIP", "VIP", "BASIC"), false, List.of(), List.of(), limits,
                List.of(), List.of(), List.of(), null, List.of());
        Candidate reserveCandidate = linkedPartialCandidate("benefit:843:policy:863:offer:offer-886883d67dd052b0",
                "씨네Q", "씨네Q(리저브관) LGU+ 멤버십", "LGU", "BASIC", reserveOffer);
        Candidate regularCandidate = linkedPartialCandidate("benefit:844:policy:864:offer:offer-bf73cbfcecc05137",
                "씨네Q", "씨네Q(일반관/리클라이너관) LGU+ 멤버십", "LGU", "BASIC", regularOffer);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"리저브관은 99,999원 할인\",\"candidateKeys\":[\"benefit:843:policy:863:offer:offer-886883d67dd052b0\",\"benefit:844:policy:864:offer:offer-bf73cbfcecc05137\"],\"suggestedQuestions\":[],\"clauseIds\":[\"clause-2\",\"clause-4\"]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan detail = new BenefitChatPlan("씨네Q 예매 할인 조건", List.of(), List.of("씨네Q"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.LGU),
                com.itplace.userapi.benefit.entity.enums.Grade.BASIC, false, "ONLINE", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(
                request("씨네Q 멤버십 혜택은 일정 금액을 넘으면 할인액이 달라져?"), detail,
                List.of(reserveCandidate, regularCandidate));

        assertThat(answer.reason()).contains("4천원 할인", "6천원 할인")
                .contains("씨네Q(리저브관)", "씨네Q(일반관/리클라이너관)")
                .doesNotContain("리저브관은 6천원 할인", "99,999원");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).stream(prompts.capture());
        assertThat(prompts.getValue().getSystemMessage().getText()).contains("정확히 포함한다(추가 후보 키 금지)");
        var clauses = new ObjectMapper().readTree(prompts.getValue().getUserMessage().getText()).path("allowedClauses");
        assertThat(clauses.findValuesAsText("text")).contains(reserve, regular);
        assertThat(clauses.findValuesAsText("offerKey")).contains("offer-886883d67dd052b0", "offer-bf73cbfcecc05137");
        assertThat(clauses.findValuesAsText("type")).contains("LIMIT");
        var regularOption = java.util.stream.StreamSupport.stream(clauses.spliterator(), false)
                .filter(clause -> regular.equals(clause.path("text").asText())).findFirst().orElseThrow();
        assertThat(regularOption.path("candidateKey").asText()).isEqualTo(regularCandidate.getCandidateKey());
        assertThat(prompts.getValue().getUserMessage().getText()).doesNotContain("normalizationIssues", "sourceBlocks");
        assertThat(clauses).allSatisfy(clause -> assertThat(clause.path("text").asText().length()).isLessThanOrEqualTo(180));
    }

    @Test
    void selectedClauseIdsRenderExactSourceAndIgnoreModelReason() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        String product = "주요 혜택은 1개월 이용권 무료 제공";
        String limit = "10,000원 이상 구매 시 3% 할인";
        String overlong = "상세 조건 " + "원문".repeat(100);
        BenefitOffer offer = new BenefitOffer("linked", "제휴 혜택", "", UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(product, overlong), List.of(), List.of(limit), List.of(), List.of(), List.of(), null, List.of());
        Candidate candidate = linkedPartialCandidate("candidate-a", "제휴처", "제휴 혜택", "SKT", "SKT_VIP", offer);
        when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"\",\"candidateKeys\":[\"candidate-a\"],\"suggestedQuestions\":[],\"clauseIds\":[\"clause-1\",\"clause-2\"]}"));
        BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);
        BenefitChatPlan detail = new BenefitChatPlan("제휴처 혜택", List.of(), List.of("제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);

        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("제휴 혜택과 할인 조건을 알려줘"), detail, List.of(candidate));

        assertThat(answer.reason()).contains("제휴 혜택: “" + product + "”", "제휴 혜택: “" + limit + "”")
                .doesNotContain("100%", "99,999원");
        assertThat(answer.candidateKeys()).containsExactly("candidate-a");
        org.mockito.Mockito.verify(model).stream(any(Prompt.class));
    }

    @Test
    void invalidUnknownAndForeignClauseIdsUseDeterministicFallback() {
        String productA = "A 제휴처에서 1개월 이용권 제공";
        String productB = "B 제휴처에서 2개월 이용권 제공";
        BenefitOffer offerA = new BenefitOffer("offer-a", "A 혜택", "", UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(productA), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        BenefitOffer offerB = new BenefitOffer("offer-b", "B 혜택", "", UsageType.ONLINE,
                List.of("SKT_VIP"), false, List.of(productB), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        Candidate a = linkedPartialCandidate("candidate-a", "A 제휴처", "A 혜택", "SKT", "SKT_VIP", offerA);
        Candidate b = linkedPartialCandidate("candidate-b", "B 제휴처", "B 혜택", "SKT", "SKT_VIP", offerB);
        BenefitChatPlan detail = new BenefitChatPlan("A와 B 혜택", List.of(), List.of("A 제휴처", "B 제휴처"), List.of(),
                List.of(com.itplace.userapi.benefit.entity.enums.Carrier.SKT),
                com.itplace.userapi.benefit.entity.enums.Grade.SKT_VIP, false, "ANY", false, "", "DETAIL", "", false);
        for (String selection : List.of(
                "\"candidateKeys\":[\"candidate-a\"],\"clauseIds\":[\"not-allowed\"]",
                "\"candidateKeys\":[\"candidate-a\"],\"clauseIds\":[\"clause-2\"]",
                "\"candidateKeys\":[],\"clauseIds\":[\"clause-1\"]")) {
            OpenAiChatModel model = mock(OpenAiChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(response("{\"reason\":\"MODEL MADE UP 99%\"," + selection + ",\"suggestedQuestions\":[]}"));
            BenefitChatAnswerer answerer = createAnswerer(model, new ObjectMapper(), "gpt-5-nano", Duration.ofSeconds(1), true);

            BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request("A와 B의 할인 조건을 알려줘"), detail, List.of(a, b));

            assertThat(answer.reason()).doesNotContain("MODEL MADE UP", "99%", "확인 가능한 원문 근거에서 찾지 못했어요");
        }
    }

    private static Flux<ChatResponse> response(String content) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private static Candidate candidate() {
        return Candidate.builder().candidateKey("benefit:1:policy:2:tier:3").benefitId(1L).partnerName("CGV")
                .benefitName("영화 할인").carrier("KT").grade("KT_VIP").manual("월 1회, 현장 예매 시 적용")
                .sourceLimitText("월 1회").conditions(new BenefitConditions(List.of(), List.of("현장 예매"),
                        List.of("월 1회"), List.of("1회 이용"), List.of("특별관 제외"))).build();
    }

    private static BenefitChatRequest request(String question) {
        return new BenefitChatRequest(question, List.of(), List.of(), null, null, null, null);
    }

    private static BenefitChatPlan plan() {
        return new BenefitChatPlan("영화 이용 횟수", List.of("영화"), List.of(), List.of(), List.of(), null,
                false, "ANY", false, "", "DETAIL", "", true);
    }
}
