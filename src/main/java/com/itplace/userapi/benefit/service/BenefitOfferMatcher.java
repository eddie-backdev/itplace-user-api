package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.reference.ReferenceDataService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BenefitOfferMatcher {
    private final ReferenceDataService referenceDataService;

    public boolean hasResolvedOffers(BenefitConditions conditions) {
        return conditions != null && (conditions.offerResolution() == OfferResolution.SINGLE
                || conditions.offerResolution() == OfferResolution.SCOPED)
                && !conditions.offers().isEmpty();
    }

    public boolean hasKnownOffers(BenefitConditions conditions) {
        return conditions != null && (conditions.offerResolution() == OfferResolution.SINGLE
                || conditions.offerResolution() == OfferResolution.SCOPED
                || conditions.offerResolution() == OfferResolution.PARTIAL)
                && !conditions.offers().isEmpty();
    }

    public boolean supports(BenefitConditions conditions, UsageType channel, Carrier carrier, List<Grade> grades) {
        if (!hasKnownOffers(conditions)) return conditions == null || conditions.offerResolution() == null;
        return conditions.offers().stream().anyMatch(offer ->
                supportsChannel(offer, channel) && supportsGrades(offer, carrier, grades));
    }

    public BenefitConditions scope(BenefitConditions conditions, UsageType channel, Carrier carrier, List<Grade> grades) {
        if (conditions == null || conditions.offers().isEmpty()) return conditions;
        if (!hasKnownOffers(conditions)) return conditions.withoutOffers();
        List<BenefitOffer> offers = conditions.offers().stream()
                .filter(offer -> supportsChannel(offer, channel))
                .filter(offer -> supportsGrades(offer, carrier, grades))
                .toList();
        return conditions.withOffers(offers);
    }

    private boolean supportsChannel(BenefitOffer offer, UsageType channel) {
        return channel == null || offer.usageType() == channel || offer.usageType() == UsageType.BOTH;
    }

    private boolean supportsGrades(BenefitOffer offer, Carrier carrier, List<Grade> grades) {
        if (grades == null || grades.isEmpty()) return true;
        if (Boolean.TRUE.equals(offer.allGrades())) return true;
        if (carrier == null || offer.gradeCodes().isEmpty()) return false;
        Set<Grade> offered = offer.gradeCodes().stream().map(code -> resolveGrade(carrier, code))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return offered.stream().anyMatch(grades::contains);
    }

    private Grade resolveGrade(Carrier carrier, String code) {
        Grade alias = referenceDataService.resolveGradeAlias(carrier, code);
        if (alias != null && referenceDataService.isBenefitGrade(carrier, alias)) return alias;
        try {
            Grade canonical = Grade.of(code);
            return referenceDataService.isBenefitGrade(carrier, canonical) ? canonical : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
