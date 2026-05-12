package com.itplace.userapi.recommend.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase1RecommendationQualityGatesTest {

    @Test
    void questionMetrics_includePhase1OfflineQualityAndGroundingGates() {
        Set<String> metricKeys = Phase1RecommendationQualityGates.questionMetrics().stream()
                .map(RecommendationEvaluationMetric::key)
                .collect(Collectors.toSet());

        assertThat(metricKeys).containsExactlyInAnyOrder(
                "question.ndcg_at_5",
                "question.recall_at_5",
                "question.top1_acceptable_rate",
                "question.grounded_violation_rate",
                "question.low_confidence_forced_category_rate"
        );
        assertThat(metric("question.ndcg_at_5").passes(0.75)).isTrue();
        assertThat(metric("question.ndcg_at_5").passes(0.72, 0.65)).isTrue();
        assertThat(metric("question.recall_at_5").passes(0.54, 0.50)).isFalse();
        assertThat(metric("question.recall_at_5").passes(0.55, 0.50)).isTrue();
        assertThat(metric("question.top1_acceptable_rate").passes(0.70)).isTrue();
        assertThat(metric("question.grounded_violation_rate").passes(0.011)).isFalse();
        assertThat(metric("question.low_confidence_forced_category_rate").passes(0.001)).isFalse();
    }

    @Test
    void personalizedMetrics_includeAttributionConversionFreshnessAndFallbackGates() {
        Set<String> metricKeys = Phase1RecommendationQualityGates.personalizedMetrics().stream()
                .map(RecommendationEvaluationMetric::key)
                .collect(Collectors.toSet());

        assertThat(metricKeys).containsExactlyInAnyOrder(
                "personalized.attribution_completeness",
                "personalized.conversion_relative_lift",
                "personalized.event_freshness_pass_rate",
                "personalized.fallback_error_rate"
        );
        assertThat(metric("personalized.attribution_completeness").passes(0.949)).isFalse();
        assertThat(metric("personalized.conversion_relative_lift").passes(0.106, 0.10)).isTrue();
        assertThat(metric("personalized.event_freshness_pass_rate").passes(1.0)).isTrue();
        assertThat(metric("personalized.fallback_error_rate").passes(0.0101)).isFalse();
    }

    @Test
    void seedFixtures_captureRequiredQuestionLabelsAndPersonalizedAttributionFields() {
        assertThat(Phase1RecommendationQualityGates.MINIMUM_LABELED_QUESTION_FIXTURE_SIZE).isEqualTo(300);
        assertThat(Phase1RecommendationEvaluationFixtures.questionSeedCases())
                .allSatisfy(questionCase -> {
                    assertThat(questionCase.id()).isNotBlank();
                    assertThat(questionCase.relevantBenefitIds()).isNotEmpty();
                    assertThat(questionCase.acceptableTop1BenefitIds()).isSubsetOf(questionCase.relevantBenefitIds());
                    assertThat(questionCase.groundingEvidenceIds()).isNotEmpty();
                });
        assertThat(Phase1RecommendationEvaluationFixtures.personalizedFunnelSeedEvents())
                .allSatisfy(event -> {
                    assertThat(event.requestId()).isNotBlank();
                    assertThat(event.impressionId()).isNotBlank();
                    assertThat(event.rank()).isPositive();
                    assertThat(event.benefitId()).isPositive();
                    assertThat(event.eventType()).isIn("impression", "click", "detail", "favorite", "use");
                });
    }

    private RecommendationEvaluationMetric metric(String key) {
        return Phase1RecommendationQualityGates.allMetrics().stream()
                .filter(metric -> metric.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
