package com.itplace.userapi.benefit.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PartnerBrowseSearchIntents {
    private static final Set<String> REQUEST_SUFFIXES = Set.of("할인", "혜택", "정보", "좀", "알려줘", "알려주세요");

    private PartnerBrowseSearchIntents() {}

    static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String normalized = keyword.replaceAll("\\s+", " ").trim();
        String original = normalized;
        while (!normalized.isEmpty()) {
            int boundary = normalized.lastIndexOf(' ');
            String lastToken = boundary < 0 ? normalized : normalized.substring(boundary + 1);
            if (!REQUEST_SUFFIXES.contains(lastToken.toLowerCase(Locale.ROOT))) break;
            normalized = boundary < 0 ? "" : normalized.substring(0, boundary).trim();
        }
        return normalized.isEmpty() ? original : normalized;
    }

    static List<String> exactPartnerNames(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of("");
        String normalized = normalizeKeyword(keyword);
        return switch (normalized == null ? "" : normalized.toLowerCase(Locale.ROOT)) {
            case "편의점" -> List.of("gs25", "cu", "세븐일레븐", "이마트24");
            case "영화", "영화관" -> List.of("cgv", "롯데시네마", "메가박스", "씨네q");
            default -> List.of("");
        };
    }
}
