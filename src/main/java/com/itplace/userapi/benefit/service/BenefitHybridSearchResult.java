package com.itplace.userapi.benefit.service;

import java.util.List;

public record BenefitHybridSearchResult(
        List<Long> benefitIds,
        long totalElements,
        int currentPage,
        int totalPages,
        boolean hasNext
) {
    public BenefitHybridSearchResult {
        benefitIds = List.copyOf(benefitIds == null ? List.of() : benefitIds);
    }
}
