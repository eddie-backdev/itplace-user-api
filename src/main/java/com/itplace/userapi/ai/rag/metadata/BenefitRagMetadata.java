package com.itplace.userapi.ai.rag.metadata;

import java.util.List;

public record BenefitRagMetadata(
        String businessType,
        List<String> useCases,
        List<String> negativeUseCases,
        List<String> tags
) {
    public BenefitRagMetadata {
        businessType = businessType == null || businessType.isBlank() ? "OTHER" : businessType;
        useCases = List.copyOf(useCases == null ? List.of() : useCases);
        negativeUseCases = List.copyOf(negativeUseCases == null ? List.of() : negativeUseCases);
        tags = List.copyOf(tags == null ? List.of() : tags);
    }
}
