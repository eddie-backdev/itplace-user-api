package com.itplace.userapi.recommend.domain;

import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserFeature {
    private Long userId;
    private Grade grade;
    private Map<String, Integer> recentCategoryScores;
    private List<String> topCategories;

    private Map<Long, Integer> benefitUsageCounts;
    private List<String> recentPartnerNames;

    private List<String> clickPartners;
    private List<String> searchPartners;
    private List<String> detailPartners;

    public String getLLMContext() {
        String categoryInfo = topCategories.isEmpty() ? "알 수 없음" : String.join(", ", topCategories);
        String benefitPartners = recentPartnerNames.isEmpty() ? "알 수 없음" : String.join(", ", recentPartnerNames);

        if (grade == null) {
            return "이 사용자는 멤버십 등급 정보가 없습니다.";
        }

        return String.format(
                "이 사용자의 멤버십 등급은 '%s'이며, 최근 '%s' 카테고리에 관심이 많고, 자주 이용한 제휴사는 [%s]입니다.",
                grade, categoryInfo, benefitPartners
        );
    }


    public String getEmbeddingText() {
        return String.format(
                """
                        이 사용자는 최근 '%s' 카테고리에 관심이 많고, 다음 제휴사를 자주 클릭하거나 검색했습니다: 클릭: [%s], 검색: [%s]
                        """,
                String.join(", ", topCategories),
                String.join(", ", clickPartners),
                String.join(", ", searchPartners)
        );
    }
}
