package com.itplace.userapi.ai.question.intent;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueryIntentExtractor {

    public QueryIntent extract(String question, Carrier requestCarrier, Grade requestGrade, double lat, double lng) {
        String normalized = normalize(question);
        Carrier carrier = requestCarrier == null ? inferCarrier(normalized) : requestCarrier;
        Grade grade = requestGrade == null ? inferGrade(normalized, carrier) : requestGrade;

        Set<String> purposeKeywords = new LinkedHashSet<>();
        Set<String> categoryHints = new LinkedHashSet<>();
        Set<String> exclusions = new LinkedHashSet<>();

        if (containsAny(normalized, "더운", "더워", "더운데", "시원", "폭염", "날씨")) {
            purposeKeywords.addAll(List.of("더위", "시원한 장소"));
            categoryHints.addAll(List.of("카페", "디저트", "빙수", "아이스크림", "영화", "쇼핑", "워터", "실내"));
            exclusions.addAll(List.of("상담", "결혼", "육아", "심리"));
        }
        if (containsAny(normalized, "카페", "커피", "디저트", "빙수", "아이스크림", "음료", "음료수", "마실", "주스", "에이드", "스무디", "차갑", "아이스")) {
            purposeKeywords.add("카페/디저트/음료");
            categoryHints.addAll(List.of("카페", "커피", "디저트", "빙수", "아이스크림", "음료", "제과", "편의점"));
        }
        if (containsAny(normalized, "음료", "음료수", "마실", "주스", "에이드", "스무디")) {
            purposeKeywords.add("음료 중심");
            exclusions.addAll(List.of("상담", "결혼", "육아", "심리", "피자", "치킨", "식당", "회관", "고기", "버거", "레스토랑"));
        }
        if (containsAny(normalized, "영화", "극장", "시네마")) {
            purposeKeywords.add("영화");
            categoryHints.addAll(List.of("영화", "극장", "시네마"));
        }
        if (containsAny(normalized, "아이", "가족", "키즈")) {
            purposeKeywords.add("가족");
            categoryHints.addAll(List.of("가족", "키즈", "식당", "외식"));
        }
        if (containsAny(normalized, "식당", "맛집", "외식", "레스토랑")) {
            purposeKeywords.add("외식");
            categoryHints.addAll(List.of("식당", "외식", "레스토랑", "음식"));
        }
        if (containsAny(normalized, "할인", "혜택", "멤버십")) {
            purposeKeywords.add("혜택");
        }

        double confidence = confidence(carrier, grade, purposeKeywords, categoryHints);
        return new QueryIntent(
                question,
                carrier,
                grade,
                new ArrayList<>(purposeKeywords),
                new ArrayList<>(categoryHints),
                new ArrayList<>(exclusions),
                hasLocation(lat, lng) ? "KNOWN" : "UNKNOWN",
                confidence,
                null
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Carrier inferCarrier(String question) {
        if (containsAny(question, "skt", "sk텔레콤", "t멤버십", "t membership")) {
            return Carrier.SKT;
        }
        if (containsAny(question, "kt")) {
            return Carrier.KT;
        }
        if (containsAny(question, "lgu", "lg u", "유플러스", "lg유플러스", "u+")) {
            return Carrier.LGU;
        }
        return null;
    }

    private static Grade inferGrade(String question, Carrier carrier) {
        if (carrier == Carrier.SKT) {
            if (containsAny(question, "vip")) {
                return Grade.SKT_VIP;
            }
            if (containsAny(question, "gold", "골드")) {
                return Grade.SKT_GOLD;
            }
            if (containsAny(question, "silver", "실버")) {
                return Grade.SKT_SILVER;
            }
        }
        if (carrier == Carrier.KT) {
            if (containsAny(question, "vvip")) {
                return Grade.KT_VVIP;
            }
            if (containsAny(question, "vip")) {
                return Grade.KT_VIP;
            }
            if (containsAny(question, "gold", "골드")) {
                return Grade.KT_GOLD;
            }
            if (containsAny(question, "silver", "실버")) {
                return Grade.KT_SILVER;
            }
            if (containsAny(question, "white", "화이트")) {
                return Grade.KT_WHITE;
            }
            if (containsAny(question, "general", "일반")) {
                return Grade.KT_GENERAL;
            }
        }
        if (carrier == Carrier.LGU || carrier == null) {
            if (containsAny(question, "vvip")) {
                return Grade.VVIP;
            }
            if (containsAny(question, "vip")) {
                return Grade.VIP;
            }
            if (containsAny(question, "basic", "베이직", "일반")) {
                return Grade.BASIC;
            }
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLocation(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && (lat != 0.0 || lng != 0.0);
    }

    private static double confidence(Carrier carrier, Grade grade, Set<String> purposeKeywords, Set<String> categoryHints) {
        double confidence = 0.35;
        if (carrier != null) {
            confidence += 0.2;
        }
        if (grade != null) {
            confidence += 0.15;
        }
        if (!purposeKeywords.isEmpty()) {
            confidence += 0.15;
        }
        if (!categoryHints.isEmpty()) {
            confidence += 0.15;
        }
        return Math.min(0.95, confidence);
    }
}
