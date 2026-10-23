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

    private BenefitFact fact(String type, String value, String unit, String basis, String basisUnit,
                             String qualifier, String sourceText) {
        return new BenefitFact(type, new BigDecimal(value), unit, type.equals("USAGE_LIMIT") ? "YEAR" : null,
                basis == null ? null : new BigDecimal(basis), basisUnit, null, qualifier, sourceText, null);
    }
}
