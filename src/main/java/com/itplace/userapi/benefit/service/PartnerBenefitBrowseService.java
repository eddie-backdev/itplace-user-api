package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse.CarrierBenefit;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse.CarrierBenefitGroup;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.BenefitPreview;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.PreviewTierBenefit;
import com.itplace.userapi.benefit.dto.response.TierBenefitInfo;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.service.StorePartnerBusinessPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartnerBenefitBrowseService {
    private final ReferenceDataService referenceDataService;
    private final StoreRepository storeRepository;
    private final BenefitOfferMatcher benefitOfferMatcher;
    private final BenefitPreviewSummaryBuilder previewSummaryBuilder;

    private static final List<Carrier> CARRIER_DISPLAY_ORDER = List.of(Carrier.SKT, Carrier.KT, Carrier.LGU);

    private final PartnerRepository partnerRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final FavoriteRepository favoriteRepository;

    public PageResult<PartnerBenefitListResponse> getPartners(
            MainCategory mainCategory,
            String category,
            UsageType filter,
            String sort,
            String keyword,
            List<Carrier> carriers,
            Grade grade,
            Pageable pageable
    ) {
        if (mainCategory != null && !referenceDataService.isActiveProgram(mainCategory.getCode())) {
            throw new InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
        }
        if (org.springframework.util.StringUtils.hasText(category) && !referenceDataService.isActiveCategory(category.trim())) {
            throw new InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
        }
        List<Carrier> carrierFilters = carriers == null ? List.of() : carriers;
        List<Grade> grades = matchingGrades(carrierFilters, grade);
        boolean carrierFilterEnabled = !carrierFilters.isEmpty();
        List<String> carrierNames = carrierFilterEnabled
                ? carrierFilters.stream().map(Carrier::name).toList()
                : CARRIER_DISPLAY_ORDER.stream().map(Carrier::name).toList();

        String searchKeyword = PartnerBrowseSearchIntents.normalizeKeyword(keyword);
        Page<Partner> partnerPage = partnerRepository.findBenefitPartners(
                mainCategory == null ? null : mainCategory.getCode(),
                normalize(category),
                filter == null ? null : filter.name(),
                searchKeyword,
                PartnerBrowseSearchIntents.exactPartnerNames(searchKeyword),
                carrierFilterEnabled,
                carrierNames,
                !grades.isEmpty(),
                grades.isEmpty() ? List.of("") : grades.stream().map(Grade::name).toList(),
                normalizeSort(sort),
                pageable
        );

        List<Long> partnerIds = partnerPage.getContent().stream().map(Partner::getPartnerId).toList();
        List<Benefit> benefits = activeBenefits(partnerIds, mainCategory);
        Map<Long, List<Benefit>> benefitsByPartner = benefits.stream()
                .collect(Collectors.groupingBy(benefit -> benefit.getPartner().getPartnerId()));
        Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policiesByBenefit(benefits);
        List<BenefitCarrierPolicy> policies = policiesByBenefit.values().stream()
                .flatMap(List::stream).toList();
        Map<Long, List<CarrierTierBenefit>> tierBenefitsByPolicy = policies.isEmpty()
                ? Map.of()
                : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                        .collect(Collectors.groupingBy(tier ->
                                tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));

        List<PartnerBenefitListResponse> content = partnerPage.getContent().stream()
                .map(partner -> PartnerBenefitListResponse.builder()
                        .partnerId(partner.getPartnerId())
                        .partnerName(partner.getPartnerName())
                        .category(partner.getCategory())
                        .image(partner.getImage())
                        .carriers(grades.isEmpty() ? availableCarriers(
                                benefitsByPartner.getOrDefault(partner.getPartnerId(), List.of()),
                                policiesByBenefit,
                                filter,
                                grades
                        ) : carrierFilters)
                        .previews(benefitPreviews(
                                benefitsByPartner.getOrDefault(partner.getPartnerId(), List.of()),
                                policiesByBenefit, tierBenefitsByPolicy, carrierFilters, grades, filter
                        ))
                        .build())
                .toList();

        return PageResult.<PartnerBenefitListResponse>builder()
                .content(content)
                .totalElements(partnerPage.getTotalElements())
                .totalPages(partnerPage.getTotalPages())
                .currentPage(partnerPage.getNumber())
                .hasNext(partnerPage.hasNext())
                .build();
    }

    public PartnerBenefitDetailResponse getPartnerDetail(
            Long partnerId, MainCategory mainCategory, Long userId, Carrier carrier, Grade grade
    ) {
        List<Grade> grades = matchingGrades(carrier == null ? List.of() : List.of(carrier), grade);
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));
        List<Benefit> benefits = activeBenefits(List.of(partnerId), mainCategory);
        if (benefits.isEmpty()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND);
        }

        List<BenefitCarrierPolicy> policies = activePolicies(benefitCarrierPolicyRepository.findAllByBenefitIn(benefits));
        if (policies.isEmpty()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND);
        }

        Map<Long, Benefit> benefitsById = benefits.stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, Function.identity()));
        Map<Long, List<CarrierTierBenefit>> tierBenefitsByPolicy = carrierTierBenefitRepository
                .findAllByBenefitCarrierPolicyIn(policies).stream()
                .collect(Collectors.groupingBy(
                        tierBenefit -> tierBenefit.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()
                ));
        List<Long> benefitIds = benefits.stream().map(Benefit::getBenefitId).toList();
        Set<Long> favoriteBenefitIds = userId == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(favoriteRepository.findFavoriteBenefitIdsByUser(userId, benefitIds));
        Map<Long, Long> favoriteCounts = favoriteRepository.countFavoritesByBenefitIds(benefitIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        Map<Carrier, List<CarrierBenefit>> benefitsByCarrier = new EnumMap<>(Carrier.class);
        for (BenefitCarrierPolicy policy : policies) {
            if (carrier != null && policy.getCarrier() != carrier) {
                continue;
            }
            if (!matchesUsageType(policy, null, grades)) continue;
            Benefit benefit = benefitsById.get(policy.getBenefit().getBenefitId());
            if (benefit == null) {
                continue;
            }
            List<CarrierTierBenefit> policyTiers = tierBenefitsByPolicy
                    .getOrDefault(policy.getBenefitCarrierPolicyId(), List.of());
            Set<String> allGradeContexts = TierBenefitSemantics.allGradeContexts(policyTiers,
                    referenceDataService.allGradeMembers(policy.getCarrier()));
            List<TierBenefitInfo> tierBenefits = policyTiers.stream()
                    .filter(tier -> grades.isEmpty() || grades.contains(tier.getGrade()))
                    .map(tier -> new TierBenefitInfo(
                            policy.getCarrier(), tier.getGrade(), tier.getContext(),
                            allGradeContexts.contains(tier.getContext())
                    ))
                    .toList();
            var scopedConditions = benefitOfferMatcher.scope(policy.getConditions(), null, policy.getCarrier(), grades);
            if (!grades.isEmpty() && tierBenefits.isEmpty()
                    && (scopedConditions == null || scopedConditions.offers().isEmpty())) {
                continue;
            }
            CarrierBenefit carrierBenefit = CarrierBenefit.builder()
                    .benefitId(benefit.getBenefitId())
                    .benefitName(policy.getDisplayBenefitName())
                    .displayName(MembershipDisplayName.commonName(
                            firstNonBlank(policy.getCarrierBenefitName(), benefit.getBenefitName()),
                            benefit.getPartner() == null ? null : benefit.getPartner().getPartnerName()))
                    .membershipOptionType(policy.getMembershipOptionType())
                    .sourceOptionLabel(policy.getSourceOptionLabel())
                    .sourceBenefitName(policy.getCarrierBenefitName())
                    .description(policy.getConditions() != null && policy.getConditions().offerResolution() != null
                            ? null : normalize(policy.getDescription()))
                    .benefitLimit(policy.getDisplayBenefitLimit())
                    .manual(normalize(policy.getManual()))
                    .url(policy.getPublicUrl())
                    .sourceUrl(normalize(policy.getSourceUrl()))
                    .sourceCapturedAt(policy.getSourceCapturedAt())
                    .conditions(scopedConditions == null ? null : scopedConditions.publicView())
                    .usageType(policy.getUsageType())
                    .tierBenefits(tierBenefits)
                    .isFavorite(favoriteBenefitIds.contains(benefit.getBenefitId()))
                    .favoriteCount(favoriteCounts.getOrDefault(benefit.getBenefitId(), 0L))
                    .build();
            benefitsByCarrier.computeIfAbsent(policy.getCarrier(), ignored -> new ArrayList<>()).add(carrierBenefit);
        }

        List<CarrierBenefitGroup> carrierGroups = CARRIER_DISPLAY_ORDER.stream()
                .filter(benefitsByCarrier::containsKey)
                .map(groupCarrier -> CarrierBenefitGroup.builder()
                        .carrier(groupCarrier)
                        .benefits(benefitsByCarrier.get(groupCarrier).stream()
                                .sorted(Comparator.comparing(CarrierBenefit::getBenefitId))
                                .toList())
                        .build())
                .toList();

        return PartnerBenefitDetailResponse.builder()
                .partnerId(partner.getPartnerId())
                .partnerName(partner.getPartnerName())
                .category(partner.getCategory())
                .image(partner.getImage())
                .hasStores(storeRepository.findActiveBusinessesByPartnerId(partnerId).stream()
                        .anyMatch(business -> StorePartnerBusinessPolicy.matches(partner.getPartnerName(), business)))
                .carrierGroups(carrierGroups)
                .build();
    }

    private List<Grade> matchingGrades(List<Carrier> carriers, Grade grade) {
        if (grade == null) {
            return List.of();
        }
        if (carriers.size() != 1 || !referenceDataService.isBenefitGrade(carriers.get(0), grade)) {
            throw new InvalidEnumException(BenefitCode.INVALID_MEMBERSHIP_FILTER);
        }
        return referenceDataService.gradesForProfile(carriers.get(0), grade);
    }

    private List<Benefit> activeBenefits(List<Long> partnerIds, MainCategory mainCategory) {
        if (partnerIds.isEmpty()) {
            return List.of();
        }
        return benefitRepository.findAllByPartnerIdsWithPartner(partnerIds).stream()
                .filter(benefit -> !Boolean.FALSE.equals(benefit.getActive()))
                .filter(benefit -> mainCategory == null || mainCategory.equals(benefit.getMainCategory()))
                .toList();
    }

    private Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit(List<Benefit> benefits) {
        if (benefits.isEmpty()) {
            return Map.of();
        }
        return activePolicies(benefitCarrierPolicyRepository.findAllByBenefitIn(benefits)).stream()
                .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
    }

    private List<BenefitCarrierPolicy> activePolicies(List<BenefitCarrierPolicy> policies) {
        return policies.stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .filter(policy -> policy.getPublicationStatus() == null
                        || policy.getPublicationStatus() == com.itplace.userapi.benefit.entity.enums.PublicationStatus.APPROVED)
                .toList();
    }

    private List<Carrier> availableCarriers(
            List<Benefit> benefits,
            Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit,
            UsageType filter,
            List<Grade> grades
    ) {
        Set<Carrier> available = benefits.stream()
                .flatMap(benefit -> policiesByBenefit.getOrDefault(benefit.getBenefitId(), List.of()).stream())
                .filter(policy -> matchesUsageType(policy, filter, grades))
                .map(BenefitCarrierPolicy::getCarrier)
                .collect(Collectors.toSet());
        return CARRIER_DISPLAY_ORDER.stream().filter(available::contains).toList();
    }

    private boolean matchesUsageType(BenefitCarrierPolicy policy, UsageType filter, List<Grade> grades) {
        if (policy.getConditions() != null
                && policy.getConditions().offerResolution() == OfferResolution.UNRESOLVED
                && filter != null) return false;
        if (policy.getConditions() != null
                && policy.getConditions().offerResolution() == OfferResolution.PARTIAL
                && filter == null) return true;
        if (benefitOfferMatcher.hasKnownOffers(policy.getConditions())) {
            return benefitOfferMatcher.supports(policy.getConditions(), filter, policy.getCarrier(), grades);
        }
        if (benefitOfferMatcher.hasResolvedOffers(policy.getConditions())) {
            return benefitOfferMatcher.supports(policy.getConditions(), filter, policy.getCarrier(), grades);
        }
        return filter == null || policy.getUsageType() == filter || policy.getUsageType() == UsageType.BOTH;
    }

    private List<BenefitPreview> benefitPreviews(
            List<Benefit> benefits,
            Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit,
            Map<Long, List<CarrierTierBenefit>> tierBenefitsByPolicy,
            List<Carrier> carriers,
            List<Grade> grades,
            UsageType filter
    ) {
        List<BenefitCarrierPolicy> policies = benefits.stream()
                .flatMap(benefit -> policiesByBenefit.getOrDefault(benefit.getBenefitId(), List.of()).stream())
                .filter(policy -> carriers.isEmpty() || carriers.contains(policy.getCarrier()))
                .filter(policy -> matchesUsageType(policy, filter, grades))
                .sorted(Comparator.comparing((BenefitCarrierPolicy policy) -> policy.getBenefit().getBenefitId())
                        .thenComparing(BenefitCarrierPolicy::getBenefitCarrierPolicyId))
                .toList();
        Map<Carrier, BenefitPreview> previews = new EnumMap<>(Carrier.class);
        for (BenefitCarrierPolicy policy : policies) {
            if (previews.containsKey(policy.getCarrier())) {
                continue;
            }
            List<CarrierTierBenefit> policyTiers = tierBenefitsByPolicy
                    .getOrDefault(policy.getBenefitCarrierPolicyId(), List.of());
            Set<String> allGradeContexts = TierBenefitSemantics.allGradeContexts(policyTiers,
                    referenceDataService.allGradeMembers(policy.getCarrier()));
            List<PreviewTierBenefit> tiers = policyTiers.stream()
                    .filter(tier -> grades.isEmpty() || grades.contains(tier.getGrade()))
                    .filter(tier -> normalize(tier.getContext()) != null)
                    .map(tier -> new PreviewTierBenefit(tier.getGrade(), tier.getContext().trim(),
                            allGradeContexts.contains(tier.getContext())))
                    .toList();
            Benefit benefit = policy.getBenefit();
            List<BenefitOffer> offers = benefitOfferMatcher.hasKnownOffers(policy.getConditions())
                    ? benefitOfferMatcher.scope(policy.getConditions(), filter, policy.getCarrier(), grades)
                    .publicView().offers()
                    : List.of();
            boolean resolvedOffers = benefitOfferMatcher.hasKnownOffers(policy.getConditions());
            boolean partial = policy.getConditions() != null
                    && policy.getConditions().offerResolution() == OfferResolution.PARTIAL;
            boolean unresolved = policy.getConditions() != null
                    && policy.getConditions().offerResolution() == OfferResolution.UNRESOLVED;
            if (tiers.isEmpty() && offers.isEmpty() && (!unresolved || !grades.isEmpty())
                    && !(partial && grades.isEmpty() && filter == null)) continue;
            UsageType previewUsageType = unresolved ? UsageType.UNKNOWN
                    : resolvedOffers ? offerUsageType(offers) : policy.getUsageType();
            previews.put(policy.getCarrier(), new BenefitPreview(
                    policy.getCarrier(), benefit.getBenefitId(),
                    policy.getDisplayBenefitName(),
                    policy.getBenefitPolicy() == null ? null : policy.getBenefitPolicy().getName(),
                    previewUsageType,
                    tiers, previewSummaryBuilder.build(policy.getConditions(), policyTiers, policy.getCarrier(), grades, filter),
                    List.of(), null, null, List.of(), List.of(),
                    normalize(policy.getSourceUrl()), policy.getSourceCapturedAt()
            ));
        }
        return CARRIER_DISPLAY_ORDER.stream().filter(previews::containsKey).map(previews::get).toList();
    }

    private UsageType offerUsageType(List<BenefitOffer> offers) {
        Set<UsageType> types = offers.stream().map(BenefitOffer::usageType).collect(Collectors.toSet());
        if (types.isEmpty() || types.contains(UsageType.UNKNOWN) || types.contains(null)) return UsageType.UNKNOWN;
        if (types.contains(UsageType.BOTH)
                || (types.contains(UsageType.ONLINE) && types.contains(UsageType.OFFLINE))) {
            return UsageType.BOTH;
        }
        return types.iterator().next();
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "POPULARITY";
        }
        return switch (sort.trim().toUpperCase()) {
            case "NAME_ASC", "NAME_DESC", "LATEST" -> sort.trim().toUpperCase();
            default -> "POPULARITY";
        };
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String first, String fallback) {
        return Optional.ofNullable(normalize(first)).orElse(fallback);
    }
}
