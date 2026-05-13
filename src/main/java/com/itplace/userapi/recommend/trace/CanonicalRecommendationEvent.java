package com.itplace.userapi.recommend.trace;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CanonicalRecommendationEvent {
    @Builder.Default
    String eventId = UUID.randomUUID().toString();
    @Builder.Default
    String schemaVersion = "recommendation-event.v1";
    String requestId;
    String impressionId;
    String userIdHash;
    String sessionId;
    RecommendationEventType eventType;
    String serviceType;
    String sourceSurface;
    String targetType;
    Long benefitId;
    Long partnerId;
    Long storeId;
    Integer rank;
    String candidateSource;
    String algorithmVersion;
    String experimentArm;
    String queryTextRedacted;
    String questionTextRedacted;
    String normalizedIntent;
    String categoryTags;
    String geoBucket;
    Map<String, Object> metadata;
    @Builder.Default
    String consentBasis = "service_contract";
    @Builder.Default
    Instant occurredAt = Instant.now();
    @Builder.Default
    Instant ingestedAt = Instant.now();

    public boolean hasRecommendationAttribution() {
        boolean hasBaseAttribution = hasText(requestId)
                && hasText(userIdHash)
                && eventType != null
                && hasText(serviceType)
                && hasText(algorithmVersion)
                && hasText(sourceSurface)
                && hasText(consentBasis);
        if (!hasBaseAttribution) {
            return false;
        }

        if (eventType == RecommendationEventType.RECOMMENDATION_REQUEST
                || eventType == RecommendationEventType.QUESTION_ASK
                || eventType == RecommendationEventType.SEARCH_SUBMIT) {
            return true;
        }

        return hasText(impressionId)
                && rank != null
                && rank > 0
                && hasText(candidateSource);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
