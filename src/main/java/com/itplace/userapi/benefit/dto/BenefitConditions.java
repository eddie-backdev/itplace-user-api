package com.itplace.userapi.benefit.dto;

import java.util.List;

public record BenefitConditions(
        List<String> products,
        List<String> availability,
        List<String> limits,
        List<String> usage,
        List<String> exclusions,
        List<BenefitOffer> offers,
        OfferResolution offerResolution,
        ChannelDecision channelDecision,
        List<BenefitSourceBlock> sourceBlocks,
        List<NormalizationIssue> normalizationIssues
) {
    public BenefitConditions(List<String> products, List<String> availability, List<String> limits,
                             List<String> usage, List<String> exclusions) {
        this(products, availability, limits, usage, exclusions, List.of(), null, null, List.of(), List.of());
    }

    public BenefitConditions(List<String> products, List<String> availability, List<String> limits,
                             List<String> usage, List<String> exclusions, List<BenefitOffer> offers,
                             OfferResolution offerResolution, ChannelDecision channelDecision) {
        this(products, availability, limits, usage, exclusions, offers, offerResolution, channelDecision,
                List.of(), List.of());
    }

    public BenefitConditions {
        products = immutable(products);
        availability = immutable(availability);
        limits = immutable(limits);
        usage = immutable(usage);
        exclusions = immutable(exclusions);
        offers = offers == null ? List.of() : List.copyOf(offers);
        sourceBlocks = sourceBlocks == null ? List.of() : List.copyOf(sourceBlocks);
        normalizationIssues = normalizationIssues == null ? List.of() : List.copyOf(normalizationIssues);
    }

    public BenefitConditions forChannel(com.itplace.userapi.benefit.entity.enums.UsageType channel) {
        if (channel == null) return this;
        if (offerResolution == OfferResolution.UNRESOLVED) return withoutOffers();
        if (offers.isEmpty()) return offerResolution == null ? this : withoutOffers();
        List<BenefitOffer> matching = offers.stream()
                .filter(offer -> offer.usageType() == channel || offer.usageType() == com.itplace.userapi.benefit.entity.enums.UsageType.BOTH)
                .toList();
        if (matching.isEmpty()) {
            return withOffers(List.of());
        }
        return withOffers(matching);
    }

    public BenefitConditions withOffers(List<BenefitOffer> scopedOffers) {
        List<BenefitOffer> matching = scopedOffers == null ? List.of() : List.copyOf(scopedOffers);
        BenefitOffer offer = matching.size() == 1 ? matching.get(0) : null;
        return new BenefitConditions(
                offer == null ? List.of() : offer.products(), offer == null ? List.of() : offer.availability(),
                offer == null ? List.of() : offer.limits(), offer == null ? List.of() : offer.usage(),
                offer == null ? List.of() : offer.exclusions(), matching, offerResolution, channelDecision,
                sourceBlocks, normalizationIssues);
    }

    public BenefitConditions withoutOffers() {
        return new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), offerResolution, channelDecision, sourceBlocks, normalizationIssues);
    }

    /** Public response copy: retain unmatched source text for user review, never its internal location. */
    public BenefitConditions publicView() {
        List<BenefitOffer> publicOffers = offers.stream().map(offer -> new BenefitOffer(
                offer.key(), offer.title(), offerResolution == OfferResolution.SINGLE ? null : offer.sourceText(),
                offer.usageType(), offer.gradeCodes(), offer.allGrades(),
                offer.products(), offer.availability(), offer.limits(), offer.usage(), offer.exclusions(),
                offer.channelEvidence(), null, offer.facts().stream().map(fact -> new BenefitFact(
                        fact.type(), fact.value(), fact.unit(), fact.period(), fact.basisAmount(), fact.basisUnit(),
                        fact.sharedLimitGroup(), fact.qualifier(), fact.sourceText(), null)).toList())).toList();
        List<NormalizationIssue> publicIssues = normalizationIssues.stream().map(issue ->
                new NormalizationIssue(issue.code(), null, issue.sourceText(), null)).distinct().toList();
        ChannelDecision publicDecision = channelDecision == null ? null : new ChannelDecision(channelDecision.version(), null,
                publicOffers.stream().flatMap(offer -> offer.channelEvidence().stream()).distinct().toList(), List.of());
        boolean legacy = offerResolution == null;
        return new BenefitConditions(legacy ? products : List.of(), legacy ? availability : List.of(),
                legacy ? limits : List.of(), legacy ? usage : List.of(), legacy ? exclusions : List.of(), publicOffers,
                offerResolution, publicDecision, List.of(), publicIssues);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
