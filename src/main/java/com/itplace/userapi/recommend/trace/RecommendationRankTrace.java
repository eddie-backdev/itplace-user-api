package com.itplace.userapi.recommend.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RecommendationRankTrace {
    String requestId;
    @Builder.Default
    String schemaVersion = "recommendation-rank-trace.v1";
    String serviceType;
    @Builder.Default
    String algorithmVersion = "es-quality-v1";
    @Builder.Default
    String experimentArm = "es_quality_v1";
    List<Long> candidateIds;
    List<String> candidateSources;
    Map<Long, Map<String, Double>> scoreComponents;
    List<Long> shownIds;
    List<String> impressionIds;
    List<String> fallbackFlags;
    Map<String, Long> latencyMs;
    Map<String, Boolean> privacyFlags;
    @Builder.Default
    Instant createdAt = Instant.now();

    public boolean isAttributionComplete() {
        return hasText(requestId)
                && hasText(serviceType)
                && hasText(algorithmVersion)
                && hasAlignedCandidates()
                && hasAlignedImpressions()
                && scoreComponents != null
                && candidateIds.stream().allMatch(scoreComponents::containsKey)
                && latencyMs != null
                && latencyMs.containsKey("total")
                && privacyFlags != null
                && privacyFlags.containsKey("text_redacted")
                && privacyFlags.containsKey("geo_bucketed");
    }

    private boolean hasAlignedCandidates() {
        return candidateIds != null
                && !candidateIds.isEmpty()
                && candidateSources != null
                && candidateSources.size() == candidateIds.size();
    }

    private boolean hasAlignedImpressions() {
        return shownIds != null
                && !shownIds.isEmpty()
                && impressionIds != null
                && impressionIds.size() == shownIds.size()
                && fallbackFlags != null
                && fallbackFlags.size() == shownIds.size();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
