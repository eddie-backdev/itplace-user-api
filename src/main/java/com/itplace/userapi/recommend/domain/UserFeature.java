package com.itplace.userapi.recommend.domain;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserFeature {
    private Long userId;
    private Carrier carrier;
    private Grade grade;
    private Map<String, Integer> recentCategoryScores;
    private List<String> topCategories;

    private Map<Long, Integer> benefitUsageCounts;
    private List<String> recentPartnerNames;

    private List<String> clickPartners;
    private List<String> searchPartners;
    private List<String> detailPartners;
    private List<String> favoritePartners;
    private List<String> impressionPartners;
    private List<String> dismissedPartners;
    private List<String> skippedPartners;
    private List<String> negativeFeedbackPartners;
    private List<String> tombstonedPartners;
    private Map<String, Double> partnerAffinityScores;
    private Map<String, Double> categoryAffinityScores;
    private Map<String, Double> negativePartnerScores;
    private String locationContext;
    private String featureSnapshotVersion;

    public String getLLMContext() {
        String categoryInfo = listOrUnknown(topCategories);
        String topBehaviorPartners = listOrUnknown(recentPartnerNames);
        String favoriteInfo = listOrUnknown(favoritePartners);
        String detailInfo = listOrUnknown(detailPartners);
        String partnerScores = topScores(partnerAffinityScores, 5);
        String negativeScores = topScores(negativePartnerScores, 5);
        String location = locationContextInfo();

        if (grade == null) {
            return String.format(
                    "이 사용자는 통신사 '%s'를 사용하고 있으며 멤버십 등급 정보가 없습니다. 위치 컨텍스트는 %s입니다. 최근 관심 카테고리는 [%s], 즐겨찾기 제휴사는 [%s], 상세보기 제휴사는 [%s], 종합 행동 점수 상위 제휴사는 [%s], 부정/피로 신호 제휴사는 [%s]입니다.",
                    carrierInfo(), location, categoryInfo, favoriteInfo, detailInfo, partnerScores, negativeScores
            );
        }

        return String.format(
                "이 사용자의 통신사는 '%s', 멤버십 등급은 '%s'이며, 위치 컨텍스트는 %s입니다. 최근 '%s' 카테고리에 관심이 많습니다. 종합 행동 점수 상위 제휴사는 [%s]이고, 즐겨찾기 제휴사는 [%s], 상세보기 제휴사는 [%s], 점수 근거는 [%s], 부정/피로 신호 제휴사는 [%s]입니다.",
                carrierInfo(), grade, location, categoryInfo, topBehaviorPartners, favoriteInfo, detailInfo, partnerScores, negativeScores
        );
    }


    public String getEmbeddingText() {
        return String.format(
                """
                        이 사용자는 최근 '%s' 카테고리에 관심이 많습니다.
                        통신사/등급 조건: %s / %s.
                        위치 컨텍스트: %s.
                        행동 신호 제휴사: 클릭 [%s], 검색 [%s], 상세보기 [%s], 즐겨찾기 [%s], 노출 [%s], 종합 행동 상위 [%s].
                        부정/피로 신호 제휴사: dismiss [%s], skip [%s], negative [%s], tombstone [%s], negativeScores [%s].
                        종합 행동 점수 상위 제휴사: [%s].
                        """,
                String.join(", ", safeList(topCategories)),
                carrierInfo(),
                grade == null ? "등급 미확인" : grade.name(),
                locationContextInfo(),
                String.join(", ", safeList(clickPartners)),
                String.join(", ", safeList(searchPartners)),
                String.join(", ", safeList(detailPartners)),
                String.join(", ", safeList(favoritePartners)),
                String.join(", ", safeList(impressionPartners)),
                String.join(", ", safeList(recentPartnerNames)),
                String.join(", ", safeList(dismissedPartners)),
                String.join(", ", safeList(skippedPartners)),
                String.join(", ", safeList(negativeFeedbackPartners)),
                String.join(", ", safeList(tombstonedPartners)),
                topScores(negativePartnerScores, 7),
                topScores(partnerAffinityScores, 7)
        );
    }

    private String carrierInfo() {
        return carrier == null ? "통신사 미확인" : carrier.name();
    }

    private String locationContextInfo() {
        return locationContext == null || locationContext.isBlank() ? "UNKNOWN" : locationContext;
    }

    public double partnerAffinity(String partnerName) {
        if (partnerName == null || partnerAffinityScores == null) {
            return 0.0;
        }
        return partnerAffinityScores.getOrDefault(partnerName, 0.0);
    }

    public double negativePartnerScore(String partnerName) {
        if (partnerName == null || negativePartnerScores == null) {
            return 0.0;
        }
        return negativePartnerScores.getOrDefault(partnerName, 0.0);
    }

    public boolean isTombstonedPartner(String partnerName) {
        return contains(tombstonedPartners, partnerName);
    }

    public double categoryAffinity(String category) {
        if (category == null || categoryAffinityScores == null) {
            return 0.0;
        }
        return categoryAffinityScores.getOrDefault(category, 0.0);
    }

    public boolean hasSignalForPartner(String partnerName) {
        return contains(clickPartners, partnerName)
                || contains(searchPartners, partnerName)
                || contains(detailPartners, partnerName)
                || contains(favoritePartners, partnerName)
                || contains(recentPartnerNames, partnerName);
    }

    private static boolean contains(List<String> values, String value) {
        return value != null && safeList(values).contains(value);
    }

    private static String listOrUnknown(List<String> values) {
        List<String> safe = safeList(values);
        return safe.isEmpty() ? "알 수 없음" : String.join(", ", safe);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String topScores(Map<String, Double> scores, int limit) {
        if (scores == null || scores.isEmpty()) {
            return "알 수 없음";
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> "%s %.1f".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", "));
    }
}
