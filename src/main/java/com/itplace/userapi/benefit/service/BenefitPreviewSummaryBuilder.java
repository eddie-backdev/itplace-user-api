package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitFact;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.BenefitPreviewSummary;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class BenefitPreviewSummaryBuilder {
    private final BenefitOfferMatcher matcher;
    public BenefitPreviewSummaryBuilder(BenefitOfferMatcher matcher) {
        this.matcher = matcher;
    }

    public BenefitPreviewSummary build(BenefitConditions conditions, List<CarrierTierBenefit> tiers,
                                       Carrier carrier, List<Grade> requestedGrades) {
        return build(conditions, tiers, carrier, requestedGrades, null);
    }

    public BenefitPreviewSummary build(BenefitConditions conditions, List<CarrierTierBenefit> tiers,
                                       Carrier carrier, List<Grade> requestedGrades, UsageType channel) {
        if (conditions == null || conditions.offerResolution() == OfferResolution.UNRESOLVED
                || !matcher.hasKnownOffers(conditions)) return null;
        List<BenefitOffer> eligible = matcher.scope(conditions, channel, carrier, requestedGrades).offers();
        if (eligible.isEmpty()) return null;

        Set<String> assignedKeys = tiers.stream().filter(t -> t.getOfferKey() != null && !t.getOfferKey().isBlank())
                .filter(t -> requestedGrades.isEmpty() || requestedGrades.contains(t.getGrade()))
                .map(CarrierTierBenefit::getOfferKey).collect(java.util.stream.Collectors.toSet());
        BenefitOffer offer;
        if (!assignedKeys.isEmpty()) {
            List<BenefitOffer> linked = eligible.stream().filter(o -> assignedKeys.contains(o.key())).toList();
            if (linked.isEmpty()) return null;
            offer = requestedGrades.stream().flatMap(grade -> linked.stream()
                    .filter(candidate -> tiers.stream().anyMatch(tier -> grade.equals(tier.getGrade())
                            && candidate.key().equals(tier.getOfferKey())))).findFirst().orElse(linked.get(0));
        } else {
            offer = eligible.get(0);
        }
        if (offer.key() == null || offer.key().isBlank()) return null;

        List<CarrierTierBenefit> linkedTiers = tiers.stream().filter(t -> offer.key().equals(t.getOfferKey()))
                .filter(t -> requestedGrades.isEmpty() || requestedGrades.contains(t.getGrade())).toList();
        List<String> grades = offer.gradeCodes();
        boolean allGrades = Boolean.TRUE.equals(offer.allGrades());
        if (!linkedTiers.isEmpty() && grades.isEmpty() && !allGrades) {
            grades = linkedTiers.stream().map(CarrierTierBenefit::getGrade).filter(java.util.Objects::nonNull)
                    .map(Grade::getCode).distinct().toList();
        }
        List<BenefitFact> facts = offer.facts();
        String reward = BenefitFactFormatter.format(facts.stream().filter(f -> isReward(f.type())).toList())
                .stream().findFirst().orElse(null);
        String linkedContext = linkedTiers.stream().map(CarrierTierBenefit::getContext).filter(java.util.Objects::nonNull)
                .map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse("");
        String linkedHeadline = linkedContext.lines().map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse("");
        List<String> productScopes = offer.products().stream().map(BenefitPreviewSummaryBuilder::brief)
                .filter(BenefitPreviewSummaryBuilder::isCompactScope).toList();
        List<String> availabilityScopes = offer.availability().stream().map(BenefitPreviewSummaryBuilder::brief)
                .filter(BenefitPreviewSummaryBuilder::isCompactScope).toList();
        boolean omittedSourceScope = !offer.products().isEmpty() && productScopes.isEmpty()
                || !offer.availability().isEmpty() && availabilityScopes.isEmpty();
        String product = productScopes.stream().findFirst().orElse("");
        String availability = availabilityScopes.stream().findFirst().orElse("");
        boolean genericTitle = offer.title() == null || offer.title().isBlank()
                || offer.title().matches("(?i)(할인형|적립형|혜택|포인트 사용)(?:\\s*[—-].*)?");
        String title = firstText(reward,
                linkedHeadline.contains("1천 원당") || linkedHeadline.contains("원당") ? brief(linkedHeadline) : null,
                genericTitle ? null : brief(offer.title()), brief(linkedHeadline), product, availability, "혜택 상세 보기");
        List<String> linkedNotes = linkedContext.lines().skip(1).map(String::trim).filter(s -> !s.isBlank()).toList();
        List<String> minSpend = BenefitFactFormatter.format(facts.stream()
                .filter(f -> "MIN_SPEND".equals(f.type())).toList());
        List<String> details = Stream.of(product, availability).filter(s -> !s.isBlank())
                .filter(s -> !s.contains(title) && !title.contains(s))
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        details.addAll(linkedNotes.stream().map(BenefitPreviewSummaryBuilder::brief).filter(BenefitPreviewSummaryBuilder::isCompactScope)
                .filter(s -> !s.equals(title)).toList());
        String condition = boundedConditions(minSpend, details, omittedSourceScope);
        List<String> typedLimits = BenefitFactFormatter.format(facts.stream()
                .filter(f -> Set.of("USAGE_LIMIT", "SPEND_CAP", "DISCOUNT_CAP").contains(f.type())).toList());
        String limit = boundedLimits(typedLimits);
        // Untagged tier text is never joined to a parsed offer: only explicitly linked rows can refine grades.
        return new BenefitPreviewSummary(offer.key(), bounded(title, 60), bounded(condition, 100), bounded(limit, 80),
                offer.usageType() == null ? UsageType.UNKNOWN : offer.usageType(), grades, allGrades, "1");
    }

    private static boolean isReward(String type) {
        return Set.of("DISCOUNT_AMOUNT", "DISCOUNT_RATE", "REWARD_RATE", "REWARD_POINTS").contains(type);
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "혜택 상세 보기";
    }

    private static String join(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct()
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static boolean isCompactScope(String value) {
        if (value == null || value.isBlank()) return false;
        String text = value.trim();
        return text.length() <= 60 && !text.matches(".*(?:습니다|됩니다|불가합니다|바랍니다|하세요|있습니다)[.!。]?");
    }

    private static String boundedConditions(List<String> required, List<String> details, boolean omittedSourceScope) {
        List<String> kept = new java.util.ArrayList<>(required);
        List<String> uniqueDetails = details.stream().distinct().toList();
        for (String detail : uniqueDetails) {
            List<String> candidate = new java.util.ArrayList<>(kept);
            candidate.add(detail);
            if (join(candidate).length() <= 100) kept.add(detail);
        }
        if (omittedSourceScope || uniqueDetails.size() > kept.size() - required.size()) {
            String note = "대상 상품·이용 조건은 상세에서 확인";
            if (join(Stream.concat(kept.stream(), Stream.of(note)).toList()).length() <= 100) kept.add(note);
        }
        return bounded(join(kept), 100);
    }

    private static String boundedLimits(List<String> values) {
        String full = join(values);
        if (full.length() <= 80) return full;
        List<String> kept = new java.util.ArrayList<>();
        for (String value : values) {
            if (join(Stream.concat(kept.stream(), Stream.of(value)).toList()).length() <= 80) kept.add(value);
        }
        String note = "기타 이용 조건은 상세에서 확인";
        if (join(Stream.concat(kept.stream(), Stream.of(note)).toList()).length() <= 80) kept.add(note);
        return join(kept);
    }

    private static String brief(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.trim();
        return clean.length() <= 60 ? clean : "";
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : "";
    }
}
