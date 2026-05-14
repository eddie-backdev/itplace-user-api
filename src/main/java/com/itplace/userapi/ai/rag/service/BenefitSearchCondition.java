package com.itplace.userapi.ai.rag.service;

import java.util.List;

public record BenefitSearchCondition(
        List<String> requiredBusinessTypes,
        List<String> excludedBusinessTypes,
        List<String> preferredUseCases,
        List<String> excludedUseCases
) {
    public BenefitSearchCondition {
        requiredBusinessTypes = List.copyOf(requiredBusinessTypes == null ? List.of() : requiredBusinessTypes);
        excludedBusinessTypes = List.copyOf(excludedBusinessTypes == null ? List.of() : excludedBusinessTypes);
        preferredUseCases = List.copyOf(preferredUseCases == null ? List.of() : preferredUseCases);
        excludedUseCases = List.copyOf(excludedUseCases == null ? List.of() : excludedUseCases);
    }

    public static BenefitSearchCondition none() {
        return new BenefitSearchCondition(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return requiredBusinessTypes.isEmpty()
                && excludedBusinessTypes.isEmpty()
                && preferredUseCases.isEmpty()
                && excludedUseCases.isEmpty();
    }
}
