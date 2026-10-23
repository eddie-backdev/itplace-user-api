package com.itplace.userapi.benefit.dto;

import java.math.BigDecimal;

public record BenefitFact(
        String type,
        BigDecimal value,
        String unit,
        String period,
        BigDecimal basisAmount,
        String basisUnit,
        String sharedLimitGroup,
        String qualifier,
        String sourceText,
        String sourcePath
) {}
