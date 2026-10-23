package com.itplace.userapi.ai.rag.service;

import java.util.List;

public record BenefitSearchCondition(
        List<String> requiredBusinessTypes,
        List<String> excludedBusinessTypes,
        List<String> preferredUseCases,
        List<String> excludedUseCases,
        List<String> requiredPartnerNames,
        List<String> excludedPartnerNames
) {
    public BenefitSearchCondition(List<String> requiredBusinessTypes, List<String> excludedBusinessTypes,
                                 List<String> preferredUseCases, List<String> excludedUseCases) {
        this(requiredBusinessTypes, excludedBusinessTypes, preferredUseCases, excludedUseCases, List.of(), List.of());
    }

    public BenefitSearchCondition {
        requiredBusinessTypes = List.copyOf(requiredBusinessTypes == null ? List.of() : requiredBusinessTypes);
        excludedBusinessTypes = List.copyOf(excludedBusinessTypes == null ? List.of() : excludedBusinessTypes);
        preferredUseCases = List.copyOf(preferredUseCases == null ? List.of() : preferredUseCases);
        excludedUseCases = List.copyOf(excludedUseCases == null ? List.of() : excludedUseCases);
        requiredPartnerNames = normalizedNames(requiredPartnerNames);
        excludedPartnerNames = normalizedNames(excludedPartnerNames);
    }

    private static List<String> normalizedNames(List<String> names) {
        return (names == null ? List.<String>of() : names).stream().filter(java.util.Objects::nonNull)
                .map(value -> {
                    String normalized = normalizePartnerName(value);
                    return normalized.isBlank() && !value.isBlank() ? "__no_canonical_partner__" : normalized;
                }).filter(name -> !name.isBlank()).distinct().toList();
    }

    public static String normalizePartnerName(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder();
        value.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    public boolean matchesPartnerName(String canonicalName) {
        String name = normalizePartnerName(canonicalName);
        return (requiredPartnerNames.isEmpty() || requiredPartnerNames.contains(name))
                && !excludedPartnerNames.contains(name);
    }

    public static BenefitSearchCondition none() {
        return new BenefitSearchCondition(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return requiredBusinessTypes.isEmpty()
                && excludedBusinessTypes.isEmpty()
                && preferredUseCases.isEmpty()
                && excludedUseCases.isEmpty()
                && requiredPartnerNames.isEmpty()
                && excludedPartnerNames.isEmpty();
    }
}
