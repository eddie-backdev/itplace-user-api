package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.dto.BenefitFact;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitFactFormatterTest {
    @Test
    void preservesRewardAndDiscountSemanticsAndBasis() {
        var facts = List.of(
                fact("REWARD_RATE", "15", "PERCENT", null, null, "EXACT", "15% 적립"),
                fact("DISCOUNT_AMOUNT", "100", "KRW", "1000", "KRW", "MAX", "1,000원당 100원 할인"),
                new BenefitFact("USAGE_LIMIT", new BigDecimal("6"), "COUNT", "YEAR", null, null,
                        "KT:VIP_CHOICE", "MAX", "초이스 연 6회", null));

        assertThat(BenefitFactFormatter.format(facts)).containsExactly(
                "15% 적립", "최대 100원 할인 (1000원당)", "VIP 초이스 통합 최대 6회/년");
    }

    @Test
    void keepsRatioBasisAndMatchesCardContextToEachRewardInSharedSourceLine() {
        String discountSource = "V G 천원당 150원 할인(모바일카드) / 천원당 50원 할인(플라스틱카드)";
        String pointsSource = "V G 천원당 100P 적립(모바일카드) / 천원당 50P 적립(플라스틱카드)";
        var facts = List.of(
                new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("150"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT", discountSource, null),
                new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT", discountSource, null),
                new BenefitFact("REWARD_POINTS", new BigDecimal("100"), "POINT", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT", pointsSource, null),
                new BenefitFact("REWARD_POINTS", new BigDecimal("50"), "POINT", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT", pointsSource, null));

        assertThat(BenefitFactFormatter.format(facts)).containsExactly(
                "150원 할인 (1000원당) · 모바일카드", "50원 할인 (1000원당) · 플라스틱카드",
                "100포인트 적립 (1000원당) · 모바일카드", "50포인트 적립 (1000원당) · 플라스틱카드");
    }

    @Test
    void onlyAddsCardScopeFromAnImmediateRewardAnnotation() {
        var facts = List.of(
                new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT",
                        "S 천원당 50원 할인(모바일카드/플라스틱카드)", null),
                new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                        new BigDecimal("1000"), "KRW", null, "EXACT",
                        "천원당 50원 할인 · 모바일카드 이용 시", null),
                new BenefitFact("MIN_SPEND", new BigDecimal("5000"), "KRW", null,
                        null, null, null, "MIN", "모바일카드 5,000원 이상 결제", null));

        assertThat(BenefitFactFormatter.format(facts)).containsExactly(
                "50원 할인 (1000원당) · 모바일카드/플라스틱카드",
                "50원 할인 (1000원당)", "최소 5000원 이상 결제");
    }

    private BenefitFact fact(String type, String value, String unit, String basis, String basisUnit,
                             String qualifier, String sourceText) {
        return new BenefitFact(type, new BigDecimal(value), unit, type.equals("USAGE_LIMIT") ? "YEAR" : null,
                basis == null ? null : new BigDecimal(basis), basisUnit, null, qualifier, sourceText, null);
    }
}
