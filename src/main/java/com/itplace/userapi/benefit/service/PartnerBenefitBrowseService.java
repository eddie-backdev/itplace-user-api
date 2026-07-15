package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse.CarrierBenefit;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse.CarrierBenefitGroup;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.dto.response.TierBenefitInfo;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.ArrayList;
import java.util.Arrays;
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
            Pageable pageable
    ) {
        List<Carrier> carrierFilters = carriers == null ? List.of() : carriers;
        boolean carrierFilterEnabled = !carrierFilters.isEmpty();
        List<String> carrierNames = carrierFilterEnabled
                ? carrierFilters.stream().map(Carrier::name).toList()
                : Arrays.stream(Carrier.values()).map(Carrier::name).toList();

        Page<Partner> partnerPage = partnerRepository.findBenefitPartners(
                mainCategory == null ? null : mainCategory.getLabel(),
                normalize(category),
                filter == null ? null : filter.name(),
                normalize(keyword),
                carrierFilterEnabled,
                carrierNames,
                normalizeSort(sort),
                pageable
        );

        List<Long> partnerIds = partnerPage.getContent().stream().map(Partner::getPartnerId).toList();
        List<Benefit> benefits = activeBenefits(partnerIds, mainCategory);
        Map<Long, List<Benefit>> benefitsByPartner = benefits.stream()
                .collect(Collectors.groupingBy(benefit -> benefit.getPartner().getPartnerId()));
        Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policiesByBenefit(benefits);

        List<PartnerBenefitListResponse> content = partnerPage.getContent().stream()
                .map(partner -> PartnerBenefitListResponse.builder()
                        .partnerId(partner.getPartnerId())
                        .partnerName(partner.getPartnerName())
                        .category(partner.getCategory())
                        .image(partner.getImage())
                        .carriers(availableCarriers(
                                benefitsByPartner.getOrDefault(partner.getPartnerId(), List.of()),
                                policiesByBenefit,
                                filter
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

    public PartnerBenefitDetailResponse getPartnerDetail(Long partnerId, MainCategory mainCategory, Long userId) {
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
            Benefit benefit = benefitsById.get(policy.getBenefit().getBenefitId());
            if (benefit == null) {
                continue;
            }
            List<TierBenefitInfo> tierBenefits = tierBenefitsByPolicy
                    .getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()).stream()
                    .map(tier -> new TierBenefitInfo(
                            policy.getCarrier(), tier.getGrade(), tier.getContext(), tier.getIsAll()
                    ))
                    .toList();
            CarrierBenefit carrierBenefit = CarrierBenefit.builder()
                    .benefitId(benefit.getBenefitId())
                    .benefitName(firstNonBlank(policy.getCarrierBenefitName(), benefit.getBenefitName()))
                    .description(normalize(policy.getDescription()))
                    .benefitLimit(policy.getBenefitPolicy() == null ? null : policy.getBenefitPolicy().getName())
                    .manual(normalize(policy.getManual()))
                    .url(normalize(policy.getUrl()))
                    .usageType(policy.getUsageType())
                    .tierBenefits(tierBenefits)
                    .isFavorite(favoriteBenefitIds.contains(benefit.getBenefitId()))
                    .favoriteCount(favoriteCounts.getOrDefault(benefit.getBenefitId(), 0L))
                    .build();
            benefitsByCarrier.computeIfAbsent(policy.getCarrier(), ignored -> new ArrayList<>()).add(carrierBenefit);
        }

        List<CarrierBenefitGroup> carrierGroups = CARRIER_DISPLAY_ORDER.stream()
                .filter(benefitsByCarrier::containsKey)
                .map(carrier -> CarrierBenefitGroup.builder()
                        .carrier(carrier)
                        .benefits(benefitsByCarrier.get(carrier).stream()
                                .sorted(Comparator.comparing(CarrierBenefit::getBenefitId))
                                .toList())
                        .build())
                .toList();

        return PartnerBenefitDetailResponse.builder()
                .partnerId(partner.getPartnerId())
                .partnerName(partner.getPartnerName())
                .category(partner.getCategory())
                .image(partner.getImage())
                .carrierGroups(carrierGroups)
                .build();
    }

    private List<Benefit> activeBenefits(List<Long> partnerIds, MainCategory mainCategory) {
        if (partnerIds.isEmpty()) {
            return List.of();
        }
        return benefitRepository.findAllByPartnerIdsWithPartner(partnerIds).stream()
                .filter(benefit -> !Boolean.FALSE.equals(benefit.getActive()))
                .filter(benefit -> mainCategory == null || benefit.getMainCategory() == mainCategory)
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
                .toList();
    }

    private List<Carrier> availableCarriers(
            List<Benefit> benefits,
            Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit,
            UsageType filter
    ) {
        Set<Carrier> available = benefits.stream()
                .flatMap(benefit -> policiesByBenefit.getOrDefault(benefit.getBenefitId(), List.of()).stream())
                .filter(policy -> matchesUsageType(policy.getUsageType(), filter))
                .map(BenefitCarrierPolicy::getCarrier)
                .collect(Collectors.toSet());
        return CARRIER_DISPLAY_ORDER.stream().filter(available::contains).toList();
    }

    private boolean matchesUsageType(UsageType usageType, UsageType filter) {
        if (filter == null) {
            return true;
        }
        return usageType == filter || usageType == UsageType.BOTH;
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
