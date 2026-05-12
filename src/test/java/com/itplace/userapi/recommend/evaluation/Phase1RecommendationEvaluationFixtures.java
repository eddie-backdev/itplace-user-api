package com.itplace.userapi.recommend.evaluation;

import java.util.List;
import java.util.Set;

/** Seed fixtures for the offline evaluator lane; intentionally small until labeled data is imported. */
final class Phase1RecommendationEvaluationFixtures {
    private Phase1RecommendationEvaluationFixtures() {
    }

    static List<QuestionCase> questionSeedCases() {
        return List.of(
                new QuestionCase(
                        "q-cafe-discount-vip",
                        "VIP가 카페에서 바로 쓸 수 있는 할인 추천해줘",
                        Set.of(1001L, 1002L),
                        Set.of(1001L),
                        Set.of("benefit:1001", "policy:vip-cafe")
                ),
                new QuestionCase(
                        "q-family-restaurant-low-confidence",
                        "아이와 갈만한 식당 혜택이 있을까?",
                        Set.of(2001L, 2002L, 2003L),
                        Set.of(2001L, 2002L),
                        Set.of("benefit:2001", "benefit:2002")
                )
        );
    }

    static List<PersonalizedFunnelEvent> personalizedFunnelSeedEvents() {
        return List.of(
                new PersonalizedFunnelEvent("request-1", "impression-1", 1, 1001L, "impression"),
                new PersonalizedFunnelEvent("request-1", "impression-1", 1, 1001L, "click"),
                new PersonalizedFunnelEvent("request-1", "impression-1", 1, 1001L, "detail"),
                new PersonalizedFunnelEvent("request-2", "impression-2", 2, 2001L, "impression"),
                new PersonalizedFunnelEvent("request-2", "impression-2", 2, 2001L, "favorite")
        );
    }

    record QuestionCase(
            String id,
            String question,
            Set<Long> relevantBenefitIds,
            Set<Long> acceptableTop1BenefitIds,
            Set<String> groundingEvidenceIds
    ) {
    }

    record PersonalizedFunnelEvent(
            String requestId,
            String impressionId,
            int rank,
            long benefitId,
            String eventType
    ) {
    }
}
