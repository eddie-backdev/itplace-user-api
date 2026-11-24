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

    public record OfferContext(String offerKey, String context) {}

    public static Set<OfferContext> allGradeOfferContexts(Collection<CarrierTierBenefit> tiers,
                                                           Collection<Grade> requiredGrades) {
        if (tiers == null || tiers.isEmpty() || requiredGrades == null || requiredGrades.isEmpty()) return Set.of();
        Map<OfferContext, Set<Grade>> gradesByOfferContext = tiers.stream()
                .filter(tier -> tier.getContext() != null && !tier.getContext().isBlank())
                .collect(Collectors.groupingBy(TierBenefitSemantics::offerContext,
                        Collectors.mapping(CarrierTierBenefit::getGrade, Collectors.toSet())));
        return allGradeKeys(gradesByOfferContext, requiredGrades);
    }

    public static OfferContext offerContext(CarrierTierBenefit tier) {
        return tier == null ? new OfferContext(null, null) : offerContext(tier.getOfferKey(), tier.getContext());
    }

    private static OfferContext offerContext(String offerKey, String context) {
        return new OfferContext(offerKey == null || offerKey.isEmpty() ? null : offerKey, context);
    }

    private static <K> Set<K> allGradeKeys(Map<K, ? extends Collection<Grade>> gradesByContext,
                                            Collection<Grade> requiredGrades) {
        if (requiredGrades == null || requiredGrades.isEmpty() || gradesByContext == null || gradesByContext.isEmpty()) {
            return Set.of();
        }

        return gradesByContext.entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(requiredGrades))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
