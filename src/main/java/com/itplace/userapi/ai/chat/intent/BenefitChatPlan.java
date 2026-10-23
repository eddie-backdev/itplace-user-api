package com.itplace.userapi.ai.chat.intent;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;

public record BenefitChatPlan(String query, List<String> categories, List<String> partners,
                              List<String> excludedPartners, List<Carrier> carriers, Grade grade,
                              boolean compareCarriers, String channel, boolean nearby, String location,
                              String mode, String clarification, boolean followUp, boolean gradeUnspecified) {
    public BenefitChatPlan(String query, List<String> categories, List<String> partners,
                           List<String> excludedPartners, List<Carrier> carriers, Grade grade,
                           boolean compareCarriers, String channel, boolean nearby, String location,
                           String mode, String clarification, boolean followUp) {
        this(query, categories, partners, excludedPartners, carriers, grade, compareCarriers, channel,
                nearby, location, mode, clarification, followUp, false);
    }

    public BenefitChatPlan {
        query = bound(query, 500);
        categories = bounded(categories, 12, 60);
        partners = bounded(partners, 12, 60);
        excludedPartners = bounded(excludedPartners, 12, 60);
        carriers = carriers == null ? List.of() : carriers.stream().filter(java.util.Objects::nonNull).distinct().limit(4).toList();
        channel = "ONLINE".equals(channel) || "OFFLINE".equals(channel) ? channel : "ANY";
        location = bound(location, 100);
        mode = List.of("SEARCH", "DETAIL", "COMPARE", "CLARIFY", "OUT_OF_SCOPE").contains(mode) ? mode : "SEARCH";
        clarification = bound(clarification, 300);
        if (clarification.isBlank() && "CLARIFY".equals(mode)) clarification = "원하시는 멤버십 혜택이나 이용 조건을 조금 더 알려주세요.";
        if (clarification.isBlank() && "OUT_OF_SCOPE".equals(mode)) clarification = "통신사 멤버십 혜택 관련 질문을 도와드릴게요. 영화나 카페처럼 분야를 말씀해 주세요.";
    }

    private static List<String> bounded(List<String> values, int size, int length) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(v -> bound(v, length))
                .filter(v -> !v.isBlank()).distinct().limit(size).toList();
    }

    private static String bound(String value, int length) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), length)).trim();
    }
}
