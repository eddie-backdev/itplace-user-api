package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-time interpretation of legacy isAll values, which may be true on grade-specific rows. */
public final class TierBenefitSemantics {

    private TierBenefitSemantics() {}

    public static Set<String> allGradeContexts(Collection<CarrierTierBenefit> tiers, Collection<Grade> requiredGrades) {
        if (tiers == null || tiers.isEmpty()) {
            return Set.of();
        }

        Map<String, Set<Grade>> gradesByContext = tiers.stream()
                .filter(tier -> tier.getContext() != null && !tier.getContext().isBlank())
                .collect(Collectors.groupingBy(CarrierTierBenefit::getContext,
                        Collectors.mapping(CarrierTierBenefit::getGrade, Collectors.toSet())));

        return allGradeContexts(gradesByContext, requiredGrades);
    }

    public static Set<String> allGradeContexts(Map<String, ? extends Collection<Grade>> gradesByContext, Collection<Grade> requiredGrades) {
        if (requiredGrades == null || requiredGrades.isEmpty() || gradesByContext == null || gradesByContext.isEmpty()) {
            return Set.of();
        }

        return gradesByContext.entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(requiredGrades))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
