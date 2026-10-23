package com.itplace.userapi.ai.chat;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared mapping from user-facing chat categories to catalog business types. */
public final class BenefitChatCategories {
    private static final Map<String, List<String>> BUSINESS_TYPES = Map.ofEntries(
            Map.entry("편의점", List.of("CONVENIENCE_STORE")),
            Map.entry("영화", List.of("MOVIE_THEATER")),
            Map.entry("카페/디저트", List.of("BEVERAGE_CAFE", "DESSERT")),
            Map.entry("카페", List.of("BEVERAGE_CAFE")),
            Map.entry("커피", List.of("BEVERAGE_CAFE")),
            Map.entry("음료", List.of("BEVERAGE_CAFE")),
            Map.entry("키즈카페", List.of("KIDS_PLAY")),
            Map.entry("스터디카페", List.of("STUDY_SPACE")),
            Map.entry("교육", List.of("EDUCATION")),
            Map.entry("학원", List.of("EDUCATION")),
            Map.entry("어학원", List.of("EDUCATION")),
            Map.entry("식당", List.of("FOOD_RESTAURANT")),
            Map.entry("음식점", List.of("FOOD_RESTAURANT")),
            Map.entry("푸드", List.of("FOOD_RESTAURANT")),
            Map.entry("외식", List.of("FOOD_RESTAURANT")),
            Map.entry("쇼핑", List.of("SHOPPING")),
            Map.entry("디저트", List.of("DESSERT")),
            Map.entry("문화", List.of("CULTURE")),
            Map.entry("문화공연", List.of("CULTURE")),
            Map.entry("공연", List.of("CULTURE")),
            Map.entry("전시", List.of("CULTURE")),
            Map.entry("놀이공원", List.of("ATTRACTION")),
            Map.entry("테마파크", List.of("ATTRACTION")),
            Map.entry("관광지", List.of("ATTRACTION")),
            Map.entry("워터파크", List.of("WATER_PARK")),
            Map.entry("수영장", List.of("WATER_PARK")),
            Map.entry("호텔", List.of("HOTEL")),
            Map.entry("숙박", List.of("HOTEL")),
            Map.entry("리조트", List.of("HOTEL")),
            Map.entry("여가", List.of("ATTRACTION", "HOTEL", "WATER_PARK", "CULTURE")),
            Map.entry("여행", List.of("ATTRACTION", "HOTEL", "WATER_PARK", "CULTURE")),
            Map.entry("여가/여행", List.of("ATTRACTION", "HOTEL", "WATER_PARK", "CULTURE")),
            Map.entry("자동차", List.of("AUTO_SERVICE")),
            Map.entry("정비", List.of("AUTO_SERVICE")),
            Map.entry("세차", List.of("AUTO_SERVICE")),
            Map.entry("상담", List.of("COUNSELING")),
            Map.entry("심리상담", List.of("COUNSELING"))
    );

    private BenefitChatCategories() {}

    public static List<String> businessTypes(String category) {
        if (category == null) return List.of();
        return BUSINESS_TYPES.getOrDefault(category.trim().toLowerCase(Locale.ROOT), List.of());
    }

    public static List<String> businessTypes(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        categories.forEach(category -> result.addAll(businessTypes(category)));
        return List.copyOf(result);
    }
}
