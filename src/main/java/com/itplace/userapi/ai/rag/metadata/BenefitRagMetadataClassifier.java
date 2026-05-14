package com.itplace.userapi.ai.rag.metadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BenefitRagMetadataClassifier {

    public BenefitRagMetadata classify(String partnerName,
                                       String category,
                                       String mainCategory,
                                       String benefitName,
                                       String description,
                                       String manual,
                                       String context) {
        String text = normalize(String.join(" ",
                blank(partnerName), blank(category), blank(mainCategory), blank(benefitName),
                blank(description), blank(manual), blank(context)
        ));
        String businessType = businessType(text);
        Set<String> useCases = new LinkedHashSet<>();
        Set<String> negativeUseCases = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();

        tags.add(businessType);
        addTokens(tags, category, mainCategory, benefitName, partnerName);

        switch (businessType) {
            case "BEVERAGE_CAFE" -> {
                useCases.addAll(List.of("음료", "커피", "디저트", "휴식", "데이트", "실내"));
                negativeUseCases.addAll(List.of("아이동반", "공부전용", "스터디", "학습공간"));
            }
            case "DESSERT" -> useCases.addAll(List.of("디저트", "빙수", "아이스크림", "음료", "데이트", "휴식"));
            case "KIDS_PLAY" -> {
                useCases.addAll(List.of("아이동반", "키즈", "실내놀이터", "가족"));
                negativeUseCases.addAll(List.of("음료카페", "조용한카페", "데이트카페", "공부"));
            }
            case "STUDY_SPACE" -> {
                useCases.addAll(List.of("공부", "스터디", "좌석이용", "학습"));
                negativeUseCases.addAll(List.of("음료카페", "데이트카페", "아이동반"));
            }
            case "MOVIE_THEATER" -> useCases.addAll(List.of("영화", "데이트", "실내", "문화"));
            case "CULTURE" -> useCases.addAll(List.of("전시", "문화", "데이트", "실내", "관람"));
            case "SHOPPING" -> useCases.addAll(List.of("쇼핑", "실내", "데이트", "휴식"));
            case "WATER_PARK" -> useCases.addAll(List.of("물놀이", "시원함", "가족", "데이트"));
            case "FOOD_RESTAURANT" -> useCases.addAll(List.of("식사", "외식", "데이트", "가족"));
            case "CONVENIENCE_STORE" -> useCases.addAll(List.of("간식", "음료", "생활편의", "근처"));
            case "COUNSELING" -> {
                useCases.addAll(List.of("상담", "심리", "케어"));
                negativeUseCases.addAll(List.of("음료", "데이트", "시원한장소", "카페"));
            }
            case "AUTO_SERVICE" -> {
                useCases.addAll(List.of("자동차", "정비", "타이어"));
                negativeUseCases.addAll(List.of("음료", "데이트", "카페", "실내휴식"));
            }
            case "HOTEL" -> useCases.addAll(List.of("숙박", "여행", "데이트"));
            case "ATTRACTION" -> useCases.addAll(List.of("관광", "체험", "데이트", "가족"));
            default -> useCases.add("혜택");
        }

        tags.addAll(useCases);
        return new BenefitRagMetadata(businessType, new ArrayList<>(useCases), new ArrayList<>(negativeUseCases), new ArrayList<>(tags));
    }

    private String businessType(String text) {
        if (containsAny(text, "키즈카페", "실내놀이터", "키즈", "어린이")) return "KIDS_PLAY";
        if (containsAny(text, "스터디카페", "독서실", "학원", "공부", "스터디")) return "STUDY_SPACE";
        if (containsAny(text, "아이스크림", "빙수", "디저트", "베이커리", "제과")) return "DESSERT";
        if (containsAny(text, "카페", "커피", "음료", "음료수", "에이드", "스무디")) return "BEVERAGE_CAFE";
        if (containsAny(text, "영화", "극장", "시네마", "cgv", "롯데시네마", "메가박스")) return "MOVIE_THEATER";
        if (containsAny(text, "전시", "미술관", "복합문화", "공연", "문화")) return "CULTURE";
        if (containsAny(text, "쇼핑", "백화점", "아울렛", "몰", "라운지")) return "SHOPPING";
        if (containsAny(text, "워터파크", "물놀이", "수영")) return "WATER_PARK";
        if (containsAny(text, "편의점", "슈퍼", "마트")) return "CONVENIENCE_STORE";
        if (containsAny(text, "상담", "심리", "결혼", "허그맘")) return "COUNSELING";
        if (containsAny(text, "자동차", "정비", "타이어", "세차")) return "AUTO_SERVICE";
        if (containsAny(text, "호텔", "숙박", "리조트")) return "HOTEL";
        if (containsAny(text, "관광", "체험", "테마", "루지", "놀이공원")) return "ATTRACTION";
        if (containsAny(text, "식당", "외식", "레스토랑", "피자", "치킨", "버거", "푸드", "경양식", "이탈리아", "회관", "고기")) return "FOOD_RESTAURANT";
        return "OTHER";
    }

    private static void addTokens(Set<String> tags, String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                tags.add(normalized);
            }
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return blank(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
