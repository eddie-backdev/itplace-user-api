package com.itplace.userapi.recommend.evaluation;

record RecommendationEvaluationMetric(
        String key,
        String description,
        double target,
        Comparison comparison,
        String observabilitySignal,
        Double baselineRelativeLiftTarget
) {
    RecommendationEvaluationMetric(
            String key,
            String description,
            double target,
            Comparison comparison,
            String observabilitySignal
    ) {
        this(key, description, target, comparison, observabilitySignal, null);
    }

    boolean passes(double actual) {
        return switch (comparison) {
            case AT_LEAST, AT_LEAST_OR_BASELINE_RELATIVE_LIFT -> actual >= target;
            case AT_MOST -> actual <= target;
            case BASELINE_RELATIVE_LIFT -> throw new IllegalStateException(
                    "Baseline value is required for metric " + key
            );
        };
    }

    boolean passes(double actual, double baseline) {
        return switch (comparison) {
            case AT_LEAST, AT_MOST -> passes(actual);
            case BASELINE_RELATIVE_LIFT -> passesRelativeLift(actual, baseline);
            case AT_LEAST_OR_BASELINE_RELATIVE_LIFT -> passes(actual) || passesRelativeLift(actual, baseline);
        };
    }

    private boolean passesRelativeLift(double actual, double baseline) {
        if (baselineRelativeLiftTarget == null) {
            throw new IllegalStateException("Relative lift target is missing for metric " + key);
        }
        return actual >= baseline * (1.0 + baselineRelativeLiftTarget);
    }

    enum Comparison {
        AT_LEAST,
        AT_MOST,
        BASELINE_RELATIVE_LIFT,
        AT_LEAST_OR_BASELINE_RELATIVE_LIFT
    }
}
