package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.BenefitFact;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            case "REWARD_POINTS" -> qualifier + value + "포인트 적립" + basis(fact);
            case "USAGE_LIMIT" -> (sharedLabel.isBlank() ? "" : sharedLabel + " 통합 ")
                    + qualifier + value + "회" + period(fact.period());
            case "SPEND_CAP" -> qualifier + value + "원 결제 한도";
            case "DISCOUNT_CAP" -> qualifier + value + "원 할인 한도";
            case "MIN_SPEND" -> qualifier + value + "원 이상 결제";
            default -> java.util.Objects.toString(fact.sourceText(), "");
        };
        if (result.isBlank() || value.isBlank()) return java.util.Objects.toString(fact.sourceText(), "");
        result = !sharedLabel.isBlank() && !"USAGE_LIMIT".equalsIgnoreCase(fact.type())
                ? result + " (" + sharedLabel + " 공통 한도)" : result;
        String cardContext = cardContext(fact);
        return cardContext.isBlank() ? result : result + " · " + cardContext;
    }

    private static String cardContext(BenefitFact fact) {
        String sourceText = java.util.Objects.toString(fact.sourceText(), "");
        String type = java.util.Objects.toString(fact.type(), "").toUpperCase(Locale.ROOT);
        String cue = switch (type) {
            case "DISCOUNT_AMOUNT" -> "\\s*원\\s*할인";
            case "REWARD_POINTS" -> "\\s*(?:P|점|포인트)\\s*적립";
            case "DISCOUNT_RATE" -> "\\s*%\\s*할인";
            case "REWARD_RATE" -> "\\s*%\\s*적립";
            default -> "";
        };
        if (cue.isBlank()) return "";
        Matcher reward = Pattern.compile("(?:^|[^0-9,.])([0-9][0-9,]*(?:\\.[0-9]+)?)" + cue,
                Pattern.CASE_INSENSITIVE).matcher(sourceText);
        while (reward.find()) {
            if (new BigDecimal(reward.group(1).replace(",", "")).compareTo(fact.value()) != 0) continue;
            Matcher annotation = Pattern.compile("^\\s*\\(([^)]*)\\)")
                    .matcher(sourceText.substring(reward.end()));
            if (!annotation.find()) continue;
            String cardSource = annotation.group(1);
            boolean mobile = Pattern.compile("모바일\\s*카드", Pattern.CASE_INSENSITIVE).matcher(cardSource).find();
            boolean plastic = Pattern.compile("플라스틱\\s*카드", Pattern.CASE_INSENSITIVE).matcher(cardSource).find();
            if (mobile && plastic) return "모바일카드/플라스틱카드";
            if (mobile) return "모바일카드";
            if (plastic) return "플라스틱카드";
        }
        return "";
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
