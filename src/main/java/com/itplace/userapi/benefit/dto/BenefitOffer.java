package com.itplace.userapi.benefit.dto;

import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;

public record BenefitOffer(
        String key,
        String title,
        String sourceText,
        UsageType usageType,
        List<String> gradeCodes,
        Boolean allGrades,
        List<String> products,
        List<String> availability,
        List<String> limits,
        List<String> usage,
        List<String> exclusions,
        List<String> channelEvidence,
        String sourcePath,
        List<BenefitFact> facts
) {
    public BenefitOffer(String key, String title, String sourceText, UsageType usageType, List<String> gradeCodes,
                        Boolean allGrades, List<String> products, List<String> availability, List<String> limits,
                        List<String> usage, List<String> exclusions, List<String> channelEvidence) {
        this(key, title, sourceText, usageType, gradeCodes, allGrades, products, availability, limits,
                usage, exclusions, channelEvidence, null, List.of());
    }

    public BenefitOffer {
        gradeCodes = immutable(gradeCodes);
        products = immutable(products);
        availability = immutable(availability);
        limits = immutable(limits);
        usage = immutable(usage);
        exclusions = immutable(exclusions);
        channelEvidence = immutable(channelEvidence);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
