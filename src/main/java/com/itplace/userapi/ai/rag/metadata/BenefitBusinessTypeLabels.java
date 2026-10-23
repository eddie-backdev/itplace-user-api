package com.itplace.userapi.ai.rag.metadata;

import java.util.Map;

/** Korean retrieval wording for the existing, source-grounded business type codes. */
public final class BenefitBusinessTypeLabels {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("CONVENIENCE_STORE", "편의점"),
            Map.entry("EDUCATION", "교육 학원"),
            Map.entry("STUDY_SPACE", "스터디카페 독서실"),
            Map.entry("KIDS_PLAY", "키즈카페 어린이 놀이시설"),
            Map.entry("BEVERAGE_CAFE", "카페 음료"),
            Map.entry("FOOD_RESTAURANT", "음식점 식당"),
            Map.entry("DESSERT", "디저트"),
            Map.entry("MOVIE_THEATER", "영화관 영화"),
            Map.entry("CULTURE", "문화 공연"),
            Map.entry("ATTRACTION", "놀이공원 관광지"),
            Map.entry("WATER_PARK", "워터파크"),
            Map.entry("HOTEL", "호텔 숙박"),
            Map.entry("AUTO_SERVICE", "자동차 정비"),
            Map.entry("COUNSELING", "상담"),
            Map.entry("SHOPPING", "쇼핑"),
            Map.entry("OTHER", "기타"));

    private BenefitBusinessTypeLabels() {}

    public static String koreanLabel(String businessType) {
        return businessType == null ? "기타" : LABELS.getOrDefault(businessType, "기타");
    }
}
