package com.itplace.userapi.ai.question.intent;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;
import java.util.UUID;

public record QueryIntent(
        String rawQuestion,
        Carrier carrier,
        Grade grade,
        List<String> purposeKeywords,
        List<String> categoryHints,
        List<String> exclusions,
        String locationContext,
        double confidence,
        String traceId
) {
    public QueryIntent {
        purposeKeywords = List.copyOf(purposeKeywords == null ? List.of() : purposeKeywords);
        categoryHints = List.copyOf(categoryHints == null ? List.of() : categoryHints);
        exclusions = List.copyOf(exclusions == null ? List.of() : exclusions);
        locationContext = locationContext == null || locationContext.isBlank() ? "UNKNOWN" : locationContext;
        traceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    public static QueryIntent of(String rawQuestion, Carrier carrier, Grade grade) {
        return new QueryIntent(rawQuestion, carrier, grade, List.of(), List.of(), List.of(), "UNKNOWN", 0.4, null);
    }

    public boolean hasIntentSignals() {
        return !purposeKeywords.isEmpty() || !categoryHints.isEmpty() || !exclusions.isEmpty();
    }

    public String retrievalText() {
        return String.join(" ",
                rawQuestion == null ? "" : rawQuestion,
                String.join(" ", purposeKeywords),
                String.join(" ", categoryHints)
        ).replaceAll("\\s+", " ").trim();
    }
}
