package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.dto.response.MapBenefitDetailResponse;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.benefit.dto.response.TierBenefitInfo;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.exception.BenefitOfflineNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StorePartnerMismatchException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitServiceImpl implements BenefitService {

    private final FavoriteRepository favoriteRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final BenefitHybridSearchService benefitHybridSearchService;
    private static final List<Carrier> CARRIER_DISPLAY_ORDER = List.of(Carrier.SKT, Carrier.KT, Carrier.LGU);

    @Override
    @Transactional(readOnly = true)
    public PageResult<BenefitListResponse> getBenefitList(
            MainCategory mainCategory,
            String category,
            UsageType filter,
            String sort,
            String keyword,
            List<Carrier> carriers,
            Long userId,
            Pageable pageable
    ) {
        List<Carrier> carrierFilters = carriers == null ? List.of() : carriers;
        boolean carrierFilterEnabled = !carrierFilters.isEmpty();
        List<String> carrierNames = carrierFilterEnabled
                ? carrierFilters.stream().map(Carrier::name).toList()
                : Arrays.stream(Carrier.values()).map(Carrier::name).toList();
        String normalizedKeyword = normalizeKeyword(keyword);
        BenefitListSort sortMode = BenefitListSort.from(sort, normalizedKeyword != null);

        if (normalizedKeyword != null && sortMode == BenefitListSort.RELEVANCE) {
            try {
                BenefitHybridSearchResult hybridResult = benefitHybridSearchService.search(
                        normalizedKeyword,
                        mainCategory,
                        category,
                        filter,
                        carrierFilters,
                        pageable
                );
                List<Benefit> orderedBenefits = expandSamePartnerBenefits(
                        orderedBenefits(hybridResult.benefitIds()),
                        mainCategory,
                        category,
                        filter,
                        carrierFilters
                );
                return toBenefitListPage(
                        orderedBenefits,
                        carrierFilters,
                        userId,
                        hybridResult.currentPage(),
                        hybridResult.totalPages(),
                        Math.max(hybridResult.totalElements(), orderedBenefits.size()),
                        hybridResult.hasNext()
                );
            } catch (RuntimeException exception) {
                log.warn("혜택 하이브리드 검색 실패로 DB 검색으로 대체합니다. keyword={}, reason={}",
                        normalizedKeyword, exception.getMessage());
            }
        }

        Page<Benefit> benefitPage = benefitRepository.findFilteredBenefits(
                mainCategory != null ? mainCategory.getLabel() : null,
                category,
                filter != null ? filter.name() : null,
                normalizedKeyword,
                carrierFilterEnabled,
                carrierNames,
                sortMode.repositoryKey(),
                pageable
        );

        return toBenefitListPage(
                benefitPage.getContent(),
                carrierFilters,
                userId,
                benefitPage.getNumber(),
                benefitPage.getTotalPages(),
                benefitPage.getTotalElements(),
                benefitPage.hasNext()
        );
    }

    private PageResult<BenefitListResponse> toBenefitListPage(
            List<Benefit> benefitList,
            List<Carrier> carrierFilters,
            Long userId,
            int currentPage,
            int totalPages,
            long totalElements,
            boolean hasNext
    ) {
        List<Long> benefitIds = benefitList.stream()
                .map(Benefit::getBenefitId)
                .toList();

        List<BenefitCarrierPolicy> policies = benefitList.isEmpty()
                ? List.of()
                : benefitCarrierPolicyRepository.findAllByBenefitIn(benefitList);
        Map<Long, List<BenefitCarrierPolicy>> policyMap = policies.stream()
                .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
        List<CarrierTierBenefit> allTierBenefits = policies.isEmpty()
                ? List.of()
                : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        Map<Long, List<CarrierTierBenefit>> tierMap = allTierBenefits.stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));

        // 즐겨찾기 여부를 한 번에 가져오기
        Set<Long> userFavoriteBenefitIds = (userId != null && !benefitIds.isEmpty())
                ? new HashSet<>(favoriteRepository.findFavoriteBenefitIdsByUser(userId, benefitIds))
                : Collections.emptySet();

        // 즐겨찾기 수를 한 번에 가져오기
        Map<Long, Long> favoriteCountMap = benefitIds.isEmpty()
                ? Map.of()
                : favoriteRepository.countFavoritesByBenefitIds(benefitIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (Long) row[1]
                        ));

        // DTO 변환
        List<BenefitListResponse> result = benefitList.stream()
                .map(b -> {
                    BenefitCarrierPolicy policy = selectListPolicy(policyMap.getOrDefault(b.getBenefitId(), List.of()), carrierFilters);
                    if (policy == null) {
                        return null;
                    }
                    Long benefitId = b.getBenefitId();
                    boolean isFavorite = userFavoriteBenefitIds.contains(benefitId);
                    long favoriteCount = favoriteCountMap.getOrDefault(benefitId, 0L);

                    List<TierBenefitInfo> tierBenefits = tierMap
                            .getOrDefault(policy.getBenefitCarrierPolicyId(), Collections.emptyList()).stream()
                            .map(tb -> new TierBenefitInfo(policy.getCarrier(), tb.getGrade(), tb.getContext(), tb.getIsAll()))
                            .toList();

                    return BenefitListResponse.builder()
                            .benefitId(benefitId)
                            .benefitName(b.getBenefitName())
                            .mainCategory(b.getMainCategory())
                            .usageType(policy.getUsageType())
                            .carrier(policy.getCarrier())
                            .active(Boolean.TRUE.equals(policy.getActive()))
                            .partnerId(b.getPartner().getPartnerId())
                            .category(Optional.ofNullable(b.getPartner().getCategory()).map(String::trim).orElse(null))
                            .image(b.getPartner().getImage())
                            .isFavorite(isFavorite)
                            .favoriteCount(favoriteCount)
                            .tierBenefits(tierBenefits)
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return PageResult.<BenefitListResponse>builder()
                .content(result)
                .currentPage(currentPage)
                .totalPages(totalPages)
                .totalElements(totalElements)
                .hasNext(hasNext)
                .build();
    }

    private List<Benefit> orderedBenefits(List<Long> benefitIds) {
        if (benefitIds == null || benefitIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Benefit> benefitsById = benefitRepository.findAllByIdWithPartner(benefitIds).stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, benefit -> benefit));
        return benefitIds.stream()
                .map(benefitsById::get)
                .filter(benefit -> benefit != null && !Boolean.FALSE.equals(benefit.getActive()))
                .toList();
    }

    private List<Benefit> expandSamePartnerBenefits(List<Benefit> rankedBenefits,
                                                    MainCategory mainCategory,
                                                    String category,
                                                    UsageType filter,
                                                    List<Carrier> carrierFilters) {
        if (rankedBenefits == null || rankedBenefits.isEmpty()) {
            return List.of();
        }

        List<Long> partnerIds = rankedBenefits.stream()
                .map(Benefit::getPartner)
                .filter(java.util.Objects::nonNull)
                .map(partner -> partner.getPartnerId())
                .distinct()
                .toList();
        if (partnerIds.isEmpty()) {
            return rankedBenefits;
        }

        List<Carrier> effectiveCarriers = carrierFilters == null || carrierFilters.isEmpty()
                ? Arrays.stream(Carrier.values()).toList()
                : carrierFilters;
        Map<Long, List<Benefit>> siblingsByPartnerId = benefitRepository
                .findActiveBenefitsByPartnerIdsWithPartner(partnerIds, mainCategory, category, filter, effectiveCarriers)
                .stream()
                .collect(Collectors.groupingBy(
                        benefit -> benefit.getPartner().getPartnerId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<Long, Benefit> expanded = new LinkedHashMap<>();
        for (Benefit rankedBenefit : rankedBenefits) {
            expanded.putIfAbsent(rankedBenefit.getBenefitId(), rankedBenefit);
            Long partnerId = rankedBenefit.getPartner() == null ? null : rankedBenefit.getPartner().getPartnerId();
            if (partnerId == null) {
                continue;
            }
            siblingsByPartnerId.getOrDefault(partnerId, List.of()).stream()
                    .sorted(Comparator
                            .comparing((Benefit benefit) -> representativeCarrierSortIndex(benefit, effectiveCarriers))
                            .thenComparing(benefit -> benefit.getBenefitId() == null ? Long.MAX_VALUE : benefit.getBenefitId()))
                    .forEach(benefit -> expanded.putIfAbsent(benefit.getBenefitId(), benefit));
        }

        return List.copyOf(expanded.values());
    }

    private int representativeCarrierSortIndex(Benefit benefit, List<Carrier> effectiveCarriers) {
        if (benefit == null || benefit.getCarrierPolicies() == null || benefit.getCarrierPolicies().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return benefit.getCarrierPolicies().stream()
                .map(BenefitCarrierPolicy::getCarrier)
                .filter(carrier -> effectiveCarriers == null || effectiveCarriers.contains(carrier))
                .mapToInt(this::carrierSortIndex)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    private enum BenefitListSort {
        RELEVANCE("RELEVANCE"),
        POPULARITY("POPULARITY"),
        NAME_ASC("NAME_ASC"),
        NAME_DESC("NAME_DESC"),
        LATEST("LATEST");

        private final String repositoryKey;

        BenefitListSort(String repositoryKey) {
            this.repositoryKey = repositoryKey;
        }

        private String repositoryKey() {
            return repositoryKey;
        }

        private static BenefitListSort from(String sort, boolean keywordSearch) {
            if (sort == null || sort.isBlank()) {
                return keywordSearch ? RELEVANCE : POPULARITY;
            }
            String normalized = sort.trim().toUpperCase().replace("-", "_");
            return switch (normalized) {
                case "RELEVANCE", "ACCURACY", "SCORE" -> RELEVANCE;
                case "NAME", "NAME_ASC", "ALPHABETICAL" -> NAME_ASC;
                case "NAME_DESC" -> NAME_DESC;
                case "LATEST", "RECENT", "NEWEST", "ID_DESC" -> LATEST;
                case "POPULARITY", "FAVORITE", "FAVORITES" -> POPULARITY;
                default -> POPULARITY;
            };
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BenefitDetailResponse getBenefitDetail(Long benefitId) {
        Benefit benefit = benefitRepository.findBenefitWithPartnerById(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        BenefitCarrierPolicy policy = representativePolicy(benefit);

        return BenefitDetailResponse.builder()
                .benefitId(benefit.getBenefitId())
                .benefitName(benefit.getBenefitName())
                .description(policy.getDescription())
                .benefitLimit(policy.getBenefitPolicy() == null ? null : policy.getBenefitPolicy().getName())
                .manual(trimNullable(policy.getManual()))
                .url(trimNullable(policy.getUrl()))
                .carrier(policy.getCarrier())
                .active(Boolean.TRUE.equals(policy.getActive()))
                .partnerName(benefit.getPartner().getPartnerName())
                .image(benefit.getPartner().getImage())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public MapBenefitDetailResponse getMapBenefitDetail(Long storeId, Long partnerId, MainCategory mainCategory,
                                                        Carrier carrier, Long userId) {
        // Store + Partner 체크
        Store store = storeRepository.findByIdAndPartnerId(storeId, partnerId)
                .orElseThrow(() -> new StorePartnerMismatchException(StoreCode.STORE_PARTNER_MISMATCH));

        // Benefit + TierBenefit 조회
        List<Benefit> benefits = benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(partnerId, mainCategory);

        if (benefits.isEmpty()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_DETAIL_NOT_FOUND);
        }

        List<BenefitCarrierPolicy> displayPolicies = findDisplayPolicies(benefits);
        BenefitCarrierPolicy selectedPolicy = selectRepresentativePolicy(displayPolicies, carrier, userId);
        if (selectedPolicy == null) {
            throw new BenefitOfflineNotFoundException(BenefitCode.BENEFIT_OFFLINE_NOT_FOUND);
        }
        Benefit selected = selectedPolicy.getBenefit();

        // Favorite 여부 체크 (선택된 Benefit에 대해서만)
        boolean isFavorite = false;
        if (userId != null) {
            isFavorite = favoriteRepository.existsByUser_IdAndBenefit_BenefitId(userId, selected.getBenefitId());
        }

        List<TierBenefitInfo> tierDtos = toDistinctTierBenefitInfosFromPolicies(displayPolicies);

        return MapBenefitDetailResponse.builder()
                .benefitId(selected.getBenefitId())
                .benefitName(selected.getBenefitName())
                .image(selected.getPartner().getImage())
                .mainCategory(selected.getMainCategory())
                .manual(trimNullable(selectedPolicy.getManual()))
                .url(trimNullable(selectedPolicy.getUrl()))
                .carrier(selectedPolicy.getCarrier())
                .tierBenefits(tierDtos)
                .isFavorite(isFavorite)
                .build();
    }

    private List<BenefitCarrierPolicy> findDisplayPolicies(List<Benefit> benefits) {
        List<BenefitCarrierPolicy> foundPolicies = benefitCarrierPolicyRepository.findAllByBenefitIn(benefits);
        if (foundPolicies == null || foundPolicies.isEmpty()) {
            foundPolicies = benefits.stream()
                    .flatMap(benefit -> benefit.getCarrierPolicies().stream())
                    .toList();
        }

        List<BenefitCarrierPolicy> policies = foundPolicies.stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getActive()))
                .filter(policy -> policy.getUsageType() == UsageType.OFFLINE || policy.getUsageType() == UsageType.BOTH)
                .sorted(Comparator
                        .comparingInt((BenefitCarrierPolicy policy) -> carrierSortIndex(policy.getCarrier()))
                        .thenComparingInt(policy -> mainCategorySortIndex(policy.getBenefit().getMainCategory()))
                        .thenComparing(policy -> policy.getBenefit().getBenefitId(), Comparator.nullsLast(Long::compareTo)))
                .toList();
        return policies.isEmpty() ? List.of() : policies;
    }

    private BenefitCarrierPolicy selectRepresentativePolicy(
            List<BenefitCarrierPolicy> policies,
            Carrier requestedCarrier,
            Long userId
    ) {
        if (policies.isEmpty()) {
            return null;
        }
        if (requestedCarrier != null) {
            Optional<BenefitCarrierPolicy> requestedCarrierPolicy = policies.stream()
                    .filter(policy -> policy.getCarrier() == requestedCarrier)
                    .findFirst();
            if (requestedCarrierPolicy.isPresent()) {
                return requestedCarrierPolicy.get();
            }
        }

        Carrier userCarrier = findUserCarrier(userId);
        if (userCarrier != null) {
            Optional<BenefitCarrierPolicy> userCarrierPolicy = policies.stream()
                    .filter(policy -> policy.getCarrier() == userCarrier)
                    .findFirst();
            if (userCarrierPolicy.isPresent()) {
                return userCarrierPolicy.get();
            }
        }

        return policies.get(0);
    }

    private Carrier findUserCarrier(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(User::getCarrier)
                .orElse(null);
    }

    private List<TierBenefitInfo> toDistinctTierBenefitInfosFromPolicies(List<BenefitCarrierPolicy> policies) {
        List<CarrierTierBenefit> tiers = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        if (tiers == null || tiers.isEmpty()) {
            tiers = policies.stream()
                    .flatMap(policy -> policy.getTierBenefits().stream())
                    .toList();
        }
        Map<Long, BenefitCarrierPolicy> policyById = policies.stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getBenefitCarrierPolicyId, policy -> policy));
        Map<String, TierBenefitInfo> distinct = new LinkedHashMap<>();
        tiers.forEach(tierBenefit -> {
            BenefitCarrierPolicy policy = policyById.get(tierBenefit.getBenefitCarrierPolicy().getBenefitCarrierPolicyId());
            if (policy == null) {
                return;
            }
            TierBenefitInfo info = new TierBenefitInfo(
                    policy.getCarrier(),
                    tierBenefit.getGrade(),
                    tierBenefit.getContext(),
                    tierBenefit.getIsAll()
            );
            String key = String.join("|",
                    String.valueOf(info.getCarrier()),
                    String.valueOf(info.getGrade()),
                    String.valueOf(info.getContext())
            );
            distinct.putIfAbsent(key, info);
        });
        return distinct.values().stream().toList();
    }

    private int carrierSortIndex(Carrier carrier) {
        if (carrier == null) {
            return CARRIER_DISPLAY_ORDER.size();
        }
        int index = CARRIER_DISPLAY_ORDER.indexOf(carrier);
        return index >= 0 ? index : CARRIER_DISPLAY_ORDER.size();
    }

    private int mainCategorySortIndex(MainCategory mainCategory) {
        if (mainCategory == MainCategory.BASIC_BENEFIT) {
            return 0;
        }
        if (mainCategory == MainCategory.VIP_COCK) {
            return 1;
        }
        return 2;
    }

    private String trimNullable(String value) {
        return value == null ? null : value.trim();
    }

    private BenefitCarrierPolicy selectListPolicy(List<BenefitCarrierPolicy> policies, List<Carrier> requestedCarriers) {
        if (policies == null || policies.isEmpty()) {
            return null;
        }
        if (requestedCarriers != null && !requestedCarriers.isEmpty()) {
            for (Carrier requestedCarrier : requestedCarriers) {
                Optional<BenefitCarrierPolicy> matchingPolicy = policies.stream()
                        .filter(policy -> policy.getCarrier() == requestedCarrier)
                        .findFirst();
                if (matchingPolicy.isPresent()) {
                    return matchingPolicy.get();
                }
            }
        }
        return policies.stream()
                .min(Comparator.comparingInt(policy -> carrierSortIndex(policy.getCarrier())))
                .orElse(policies.get(0));
    }

    private BenefitCarrierPolicy representativePolicy(Benefit benefit) {
        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit));
        if (policies == null || policies.isEmpty()) {
            policies = benefit.getCarrierPolicies();
        }
        return policies.stream()
                .findFirst()
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
    }

}
