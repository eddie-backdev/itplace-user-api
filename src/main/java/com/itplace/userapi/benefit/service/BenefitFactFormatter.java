package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.BenefitFact;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** Formats only explicit typed source facts; unknown fact types remain visible as source text. */
public final class BenefitFactFormatter {
    private BenefitFactFormatter() {}

    public static List<String> format(List<BenefitFact> facts) {
        if (facts == null) return List.of();
        return facts.stream().map(BenefitFactFormatter::format).filter(s -> !s.isBlank()).distinct().toList();
    }

    private static String format(BenefitFact fact) {
        if (fact == null) return "";
        String qualifier = "MAX".equals(fact.qualifier()) ? "최대 " : "MIN".equals(fact.qualifier()) ? "최소 " : "";
        String value = number(fact.value());
        String sharedLabel = sharedLabel(fact.sharedLimitGroup());
        String result = switch (java.util.Objects.toString(fact.type(), "").toUpperCase(Locale.ROOT)) {
            case "DISCOUNT_AMOUNT" -> qualifier + value + "원 할인" + basis(fact);
            case "DISCOUNT_RATE" -> qualifier + value + "% 할인";
            case "REWARD_RATE" -> qualifier + value + "% 적립";
            case "REWARD_POINTS" -> qualifier + value + "포인트 적립";
            case "USAGE_LIMIT" -> (sharedLabel.isBlank() ? "" : sharedLabel + " 통합 ")
                    + qualifier + value + "회" + period(fact.period());
            case "SPEND_CAP" -> qualifier + value + "원 결제 한도";
            case "DISCOUNT_CAP" -> qualifier + value + "원 할인 한도";
            case "MIN_SPEND" -> qualifier + value + "원 이상 결제";
            default -> java.util.Objects.toString(fact.sourceText(), "");
        };
        if (result.isBlank() || value.isBlank()) return java.util.Objects.toString(fact.sourceText(), "");
        return !sharedLabel.isBlank() && !"USAGE_LIMIT".equalsIgnoreCase(fact.type())
                ? result + " (" + sharedLabel + " 공통 한도)" : result;
    }

    private static String basis(BenefitFact fact) {
        return fact.basisAmount() == null ? "" : " (" + number(fact.basisAmount())
                + unit(fact.basisUnit()) + "당)";
    }

    private static String unit(String value) {
        return switch (java.util.Objects.toString(value, "").toUpperCase(Locale.ROOT)) {
            case "KRW" -> "원";
            case "PERCENT" -> "%";
            case "POINT" -> "포인트";
            case "COUNT" -> "회";
            default -> java.util.Objects.toString(value, "");
        };
    }

    private static String sharedLabel(String value) {
        if (value == null || value.isBlank()) return "";
        String key = value.substring(value.lastIndexOf(':') + 1).toUpperCase(Locale.ROOT);
        return switch (key) {
            case "VIP_CHOICE" -> "VIP 초이스";
            case "VVIP_CHOICE" -> "VVIP 초이스";
            default -> "공통";
        };
    }

    private static String period(String period) {
        return switch (java.util.Objects.toString(period, "").toUpperCase(Locale.ROOT)) {
            case "DAY" -> "/일";
            case "MONTH" -> "/월";
            case "YEAR" -> "/년";
            case "TOTAL" -> "/총";
            case "TRANSACTION" -> "/회당";
            default -> "";
        };
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
