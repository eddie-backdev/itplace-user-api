package com.itplace.userapi.recommend.evaluation;

import java.util.List;

/**
 * Phase 1 ES-only recommendation quality gates from the AI recommendation advancement PRD/test spec.
 *
 * <p>This test-scope scaffold keeps the release thresholds executable without touching production
 * recommendation workers. Production wiring can later map these keys to Micrometer/dashboard names.</p>
 */
final class Phase1RecommendationQualityGates {
    static final int MINIMUM_LABELED_QUESTION_FIXTURE_SIZE = 300;
    static final double QUESTION_NDCG_AT_5_ABSOLUTE_TARGET = 0.75;
    static final double QUESTION_BASELINE_RELATIVE_LIFT_TARGET = 0.10;
    static final double QUESTION_TOP1_ACCEPTABLE_TARGET = 0.70;
    static final double QUESTION_GROUNDED_VIOLATION_MAX = 0.01;
    static final double PERSONALIZED_ATTRIBUTION_COMPLETENESS_TARGET = 0.95;
    static final double PERSONALIZED_CONVERSION_RELATIVE_LIFT_TARGET = 0.05;
    static final double PERSONALIZED_FALLBACK_ERROR_MAX = 0.01;

    private Phase1RecommendationQualityGates() {
    }

    static List<RecommendationEvaluationMetric> questionMetrics() {
        return List.of(
                new RecommendationEvaluationMetric(
                        "question.ndcg_at_5",
                        "질문형 추천 NDCG@5 absolute gate",
                        QUESTION_NDCG_AT_5_ABSOLUTE_TARGET,
                        RecommendationEvaluationMetric.Comparison.AT_LEAST_OR_BASELINE_RELATIVE_LIFT,
                        "offline.question.ndcg_at_5",
                        QUESTION_BASELINE_RELATIVE_LIFT_TARGET
                ),
                new RecommendationEvaluationMetric(
                        "question.recall_at_5",
                        "질문형 추천 Recall@5 baseline +10% relative gate",
                        0.0,
                        RecommendationEvaluationMetric.Comparison.BASELINE_RELATIVE_LIFT,
                        "offline.question.recall_at_5",
                        QUESTION_BASELINE_RELATIVE_LIFT_TARGET
                ),
                new RecommendationEvaluationMetric(
                        "question.top1_acceptable_rate",
                        "top-1 acceptable rate",
                        QUESTION_TOP1_ACCEPTABLE_TARGET,
                        RecommendationEvaluationMetric.Comparison.AT_LEAST,
                        "offline.question.top1_acceptable_rate"
                ),
                new RecommendationEvaluationMetric(
                        "question.grounded_violation_rate",
                        "grounded explanation violation rate",
                        QUESTION_GROUNDED_VIOLATION_MAX,
                        RecommendationEvaluationMetric.Comparison.AT_MOST,
                        "recommendation.grounded_violation"
                ),
                new RecommendationEvaluationMetric(
                        "question.low_confidence_forced_category_rate",
                        "low-confidence requests must not force a single category",
                        0.0,
                        RecommendationEvaluationMetric.Comparison.AT_MOST,
                        "recommendation.low_confidence_forced_category"
                )
        );
    }

    static List<RecommendationEvaluationMetric> personalizedMetrics() {
        return List.of(
                new RecommendationEvaluationMetric(
                        "personalized.attribution_completeness",
                        "request/impression/rank attribution completeness",
                        PERSONALIZED_ATTRIBUTION_COMPLETENESS_TARGET,
                        RecommendationEvaluationMetric.Comparison.AT_LEAST,
                        "recommendation.attribution_completeness"
                ),
                new RecommendationEvaluationMetric(
                        "personalized.conversion_relative_lift",
                        "primary conversion funnel relative lift before online ramp",
                        0.0,
                        RecommendationEvaluationMetric.Comparison.BASELINE_RELATIVE_LIFT,
                        "recommendation.conversion.primary_lift",
                        PERSONALIZED_CONVERSION_RELATIVE_LIFT_TARGET
                ),
                new RecommendationEvaluationMetric(
                        "personalized.event_freshness_pass_rate",
                        "high-signal events refresh recommendations within freshness gate",
                        1.0,
                        RecommendationEvaluationMetric.Comparison.AT_LEAST,
                        "recommendation.event_freshness_pass_rate"
                ),
                new RecommendationEvaluationMetric(
                        "personalized.fallback_error_rate",
                        "fallback/error rate",
                        PERSONALIZED_FALLBACK_ERROR_MAX,
                        RecommendationEvaluationMetric.Comparison.AT_MOST,
                        "recommendation.fallback_error_rate"
                )
        );
    }

    static List<RecommendationEvaluationMetric> allMetrics() {
        return List.of(
                questionMetrics().get(0),
                questionMetrics().get(1),
                questionMetrics().get(2),
                questionMetrics().get(3),
                questionMetrics().get(4),
                personalizedMetrics().get(0),
                personalizedMetrics().get(1),
                personalizedMetrics().get(2),
                personalizedMetrics().get(3)
        );
    }
}
