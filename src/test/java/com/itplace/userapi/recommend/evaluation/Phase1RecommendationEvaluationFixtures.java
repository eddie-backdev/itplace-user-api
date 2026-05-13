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
                        Set.of("benefit:1001", "policy:vip-cafe"),
                        Set.of("carrier:any", "grade:vip", "intent:cafe-discount"),
                        Set.of()
                ),
                new QuestionCase(
                        "q-family-restaurant-low-confidence",
                        "아이와 갈만한 식당 혜택이 있을까?",
                        Set.of(2001L, 2002L, 2003L),
                        Set.of(2001L, 2002L),
                        Set.of("benefit:2001", "benefit:2002"),
                        Set.of("intent:family-restaurant", "confidence:low"),
                        Set.of("single-forced-category")
                ),
                new QuestionCase(
                        "q-hot-cool-place-false-route",
                        "요즘 핫하고 시원한 곳에서 쓸 수 있는 혜택 알려줘",
                        Set.of(3001L, 3002L, 3003L),
                        Set.of(3001L, 3002L),
                        Set.of("benefit:3001", "merchant:trend-place", "review:popular-place"),
                        Set.of("intent:place-discovery", "false-route:hot-cool-place"),
                        Set.of("weather", "temperature", "air-conditioning")
                ),
                new QuestionCase(
                        "q-skt-vip-grade-benefit",
                        "SKT VIP 등급으로 받을 수 있는 영화 혜택 추천해줘",
                        Set.of(4101L, 4102L),
                        Set.of(4101L),
                        Set.of("benefit:4101", "carrier:skt", "grade:vip"),
                        Set.of("carrier:skt", "grade:vip", "intent:carrier-grade"),
                        Set.of("carrier:kt", "carrier:lgu", "grade:all")
                ),
                new QuestionCase(
                        "q-kt-basic-grade-benefit",
                        "KT 일반 등급도 쓸 수 있는 편의점 혜택 있어?",
                        Set.of(4201L, 4202L),
                        Set.of(4201L),
                        Set.of("benefit:4201", "carrier:kt", "grade:basic"),
                        Set.of("carrier:kt", "grade:basic", "intent:carrier-grade"),
                        Set.of("carrier:skt", "carrier:lgu", "grade:vip-only")
                ),
                new QuestionCase(
                        "q-lgu-all-grade-benefit",
                        "LGU+ 멤버십 전체 등급 공통으로 가능한 외식 혜택 알려줘",
                        Set.of(4301L, 4302L, 4303L),
                        Set.of(4301L, 4302L),
                        Set.of("benefit:4301", "carrier:lgu", "grade:all"),
                        Set.of("carrier:lgu", "grade:all", "intent:carrier-grade"),
                        Set.of("carrier:skt", "carrier:kt", "grade:vip-only")
                ),
                new QuestionCase(
                        "q-broad-valid-recall-safety",
                        "근처에서 지금 쓸 수 있는 혜택을 넓게 추천해줘",
                        Set.of(5101L, 5102L, 5103L, 5104L, 5105L),
                        Set.of(5101L, 5102L, 5103L),
                        Set.of("benefit:5101", "benefit:5102", "benefit:5103", "location:known"),
                        Set.of("recall-safety:broad-valid", "intent:nearby-benefits"),
                        Set.of("empty-result", "single-forced-category")
                )
        );
    }

    static List<PersonalizedFunnelEvent> personalizedFunnelSeedEvents() {
        return List.of(
                new PersonalizedFunnelEvent("request-known-1", "impression-known-1", 1, 1001L, "impression", "KNOWN"),
                new PersonalizedFunnelEvent("request-known-1", "impression-known-1", 1, 1001L, "click", "KNOWN"),
                new PersonalizedFunnelEvent("request-known-1", "impression-known-1", 1, 1001L, "detail", "KNOWN"),
                new PersonalizedFunnelEvent("request-known-1", "impression-known-1", 1, 1001L, "favorite", "KNOWN"),
                new PersonalizedFunnelEvent("request-unknown-1", "impression-unknown-1", 2, 2001L, "impression", "UNKNOWN"),
                new PersonalizedFunnelEvent("request-unknown-1", "impression-unknown-1", 2, 2001L, "dismiss", "UNKNOWN"),
                new PersonalizedFunnelEvent("request-unknown-1", "impression-unknown-1", 2, 2001L, "skip", "UNKNOWN"),
                new PersonalizedFunnelEvent("request-unknown-2", "impression-unknown-2", 3, 2002L, "impression", "UNKNOWN"),
                new PersonalizedFunnelEvent("request-unknown-2", "impression-unknown-2", 3, 2002L, "negative", "UNKNOWN")
        );
    }

    record QuestionCase(
            String id,
            String question,
            Set<Long> relevantBenefitIds,
            Set<Long> acceptableTop1BenefitIds,
            Set<String> groundingEvidenceIds,
            Set<String> labelDimensions,
            Set<String> disallowedRouteHints
    ) {
    }

    record PersonalizedFunnelEvent(
            String requestId,
            String impressionId,
            int rank,
            long benefitId,
            String eventType,
            String locationContext
    ) {
    }
}
