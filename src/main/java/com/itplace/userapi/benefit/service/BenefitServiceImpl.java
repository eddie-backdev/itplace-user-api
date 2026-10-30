package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.dto.response.BenefitPolicyDetail;
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
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.benefit.support.BenefitContextSplitter;
import com.itplace.userapi.benefit.support.BenefitPolicySelector;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StorePartnerMismatchException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.service.StorePartnerBusinessPolicy;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitServiceImpl implements BenefitService {
    private final ReferenceDataService referenceDataService;

    private final FavoriteRepository favoriteRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final BenefitHybridSearchService benefitHybridSearchService;
    private static final List<Carrier> CARRIER_DISPLAY_ORDER = List.of(Carrier.SKT, Carrier.KT, Carrier.LGU);
    private static final int SEARCH_EXPANSION_FETCH_LIMIT = 500;

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
        validateReferenceFilters(mainCategory, category);
        List<Carrier> carrierFilters = carriers == null ? List.of() : carriers;
        boolean carrierFilterEnabled = !carrierFilters.isEmpty();
        List<String> carrierNames = carrierFilterEnabled
                ? carrierFilters.stream().map(Carrier::name).toList()
                : CARRIER_DISPLAY_ORDER.stream().map(Carrier::name).toList();
        String normalizedKeyword = normalizeKeyword(keyword);
        BenefitListSort sortMode = BenefitListSort.from(sort, normalizedKeyword != null);

        if (normalizedKeyword != null && sortMode == BenefitListSort.RELEVANCE) {
            BenefitHybridSearchResult hybridResult = null;
            try {
                hybridResult = benefitHybridSearchService.search(
                        normalizedKeyword,
                        mainCategory,
                        category,
                        filter,
                        carrierFilters,
                        expansionFetchPageable()
                );
            } catch (RuntimeException exception) {
                log.warn("혜택 하이브리드 검색 실패로 DB 검색으로 대체합니다. errorType={}",
                        exception.getClass().getSimpleName());
            }
            if (hybridResult != null) {
                List<Benefit> orderedBenefits = expandSamePartnerBenefits(
                        orderedBenefits(hybridResult.benefitIds()),
                        mainCategory,
                        category,
                        filter,
                        carrierFilters
                );
                BenefitPage<Benefit> benefitPage = sliceBenefits(
                        orderedBenefits,
                        pageable
                );
                return toBenefitListPage(
                        benefitPage.content(),
                        carrierFilters,
                    filter,
                        userId,
                        benefitPage.currentPage(),
                        benefitPage.totalPages(),
                        benefitPage.totalElements(),
                        benefitPage.hasNext()
                );
            }
        }
        if (normalizedKeyword != null && sortMode != BenefitListSort.RELEVANCE) {
            BenefitHybridSearchResult lexicalResult = null;
            try {
                lexicalResult = benefitHybridSearchService.searchLexical(
                        normalizedKeyword,
                        mainCategory,
                        category,
                        filter,
                        carrierFilters,
                        expansionFetchPageable()
                );
            } catch (RuntimeException exception) {
                log.warn("혜택 lexical 검색 실패로 DB 검색으로 대체합니다. sort={}, errorType={}",
                        sortMode.repositoryKey(), exception.getClass().getSimpleName());
            }
            if (lexicalResult != null) {
                List<Benefit> sortedBenefits = sortBenefits(
                        orderedBenefits(lexicalResult.benefitIds()),
                        sortMode
                );
                List<Benefit> expandedBenefits = expandSamePartnerBenefits(
                        sortedBenefits,
                        mainCategory,
                        category,
                        filter,
                        carrierFilters
                );
                BenefitPage<Benefit> slicedPage = sliceBenefits(
                        expandedBenefits,
                        pageable
                );
                return toBenefitListPage(
                        slicedPage.content(),
                        carrierFilters,
                    filter,
                        userId,
                        slicedPage.currentPage(),
                        slicedPage.totalPages(),
                        slicedPage.totalElements(),
                        slicedPage.hasNext()
                );
            }
        }

        Pageable repositoryPageable = normalizedKeyword == null ? pageable : expansionFetchPageable();
        Page<Benefit> benefitPage = benefitRepository.findFilteredBenefits(
                mainCategory != null ? mainCategory.getCode() : null,
                category,
                filter != null ? filter.name() : null,
                normalizedKeyword,
                carrierFilterEnabled,
                carrierNames,
                sortMode.repositoryKey(),
                repositoryPageable
        );
        List<Benefit> benefitContent = normalizedKeyword == null
                ? hydratePartners(benefitPage.getContent())
                : expandSamePartnerBenefits(
                        benefitPage.getContent(),
                        mainCategory,
                        category,
                        filter,
                        carrierFilters
                );
        if (normalizedKeyword != null) {
            BenefitPage<Benefit> slicedPage = sliceBenefits(
                    benefitContent,
                    pageable
            );
            return toBenefitListPage(
                    slicedPage.content(),
                    carrierFilters,
                    filter,
                    userId,
                    slicedPage.currentPage(),
                    slicedPage.totalPages(),
                    slicedPage.totalElements(),
                    slicedPage.hasNext()
            );
        }

        return toBenefitListPage(
                benefitContent,
                carrierFilters,
                filter,
                userId,
                benefitPage.getNumber(),
                benefitPage.getTotalPages(),
                Math.max(benefitPage.getTotalElements(), benefitContent.size()),
                benefitPage.hasNext()
        );
    }

    private Pageable expansionFetchPageable() {
        return PageRequest.of(0, SEARCH_EXPANSION_FETCH_LIMIT);
    }

    private <T> BenefitPage<T> sliceBenefits(List<T> benefits, Pageable pageable) {
        List<T> safeBenefits = benefits == null ? List.of() : benefits;
        int pageSize = Math.max(pageable.getPageSize(), 1);
        long offset = pageable.getOffset();
        int fromIndex = (int) Math.min(offset, safeBenefits.size());
        int toIndex = Math.min(fromIndex + pageSize, safeBenefits.size());
        // ponytail: keyword search pages the bounded candidate set; use DB pagination if exhaustive search is required.
        long totalElements = safeBenefits.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);
        boolean hasNext = offset + pageSize < totalElements;
        return new BenefitPage<>(
                safeBenefits.subList(fromIndex, toIndex),
                pageable.getPageNumber(),
                totalPages,
                totalElements,
                hasNext
        );
    }

    private PageResult<BenefitListResponse> toBenefitListPage(
            List<Benefit> benefitList,
            List<Carrier> carrierFilters,
            UsageType filter,
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
                    BenefitCarrierPolicy policy = selectListPolicy(policyMap.getOrDefault(b.getBenefitId(), List.of()), carrierFilters, filter);
                    if (policy == null) {
                        return null;
                    }
                    Long benefitId = b.getBenefitId();
                    boolean isFavorite = userFavoriteBenefitIds.contains(benefitId);
                    long favoriteCount = favoriteCountMap.getOrDefault(benefitId, 0L);

                    List<CarrierTierBenefit> policyTiers = tierMap
                            .getOrDefault(policy.getBenefitCarrierPolicyId(), Collections.emptyList());
                    Set<String> allGradeContexts = TierBenefitSemantics.allGradeContexts(
                            policyTiers, referenceDataService.allGradeMembers(policy.getCarrier()));
                    List<TierBenefitInfo> tierBenefits = policyTiers.stream()
                            .map(tb -> new TierBenefitInfo(
                                    policy.getCarrier(), tb.getGrade(), tb.getContext(),
                                    allGradeContexts.contains(tb.getContext())))
                            .toList();

                    return BenefitListResponse.builder()
                            .benefitId(benefitId)
                            .benefitName(policy.getDisplayBenefitName())
                            .mainCategory(b.getMainCategory())
                            .usageType(policy.getUsageType())
                            .carrier(policy.getCarrier())
                            .active(!Boolean.FALSE.equals(policy.getActive()))
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

    private List<Benefit> hydratePartners(List<Benefit> benefits) {
        if (benefits == null || benefits.isEmpty()) {
            return List.of();
        }

        List<Long> benefitIds = benefits.stream()
                .map(Benefit::getBenefitId)
                .toList();
        Map<Long, Benefit> hydratedById = benefitRepository.findAllByIdWithPartner(benefitIds).stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, benefit -> benefit));

        return benefits.stream()
                .map(benefit -> hydratedById.getOrDefault(benefit.getBenefitId(), benefit))
                .toList();
    }

    private List<Benefit> sortBenefits(List<Benefit> benefits, BenefitListSort sortMode) {
        if (benefits == null || benefits.isEmpty()) {
            return List.of();
        }
        return switch (sortMode) {
            case LATEST -> benefits.stream()
                    .sorted(Comparator
                            .comparing((Benefit benefit) -> benefit.getBenefitId() == null ? Long.MIN_VALUE : benefit.getBenefitId())
                            .reversed())
                    .toList();
            case NAME_ASC -> benefits.stream()
                    .sorted(Comparator.comparing(this::normalizedBenefitName)
                            .thenComparing(benefit -> benefit.getBenefitId() == null ? Long.MAX_VALUE : benefit.getBenefitId()))
                    .toList();
            case NAME_DESC -> benefits.stream()
                    .sorted(Comparator.comparing(this::normalizedBenefitName).reversed()
                            .thenComparing(benefit -> benefit.getBenefitId() == null ? Long.MAX_VALUE : benefit.getBenefitId()))
                    .toList();
            case POPULARITY -> sortByFavoriteCount(benefits);
            case RELEVANCE -> benefits;
        };
    }

    private List<Benefit> sortByFavoriteCount(List<Benefit> benefits) {
        List<Long> benefitIds = benefits.stream()
                .map(Benefit::getBenefitId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, Long> favoriteCountMap = benefitIds.isEmpty()
                ? Map.of()
                : favoriteRepository.countFavoritesByBenefitIds(benefitIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (Long) row[1]
                        ));
        return benefits.stream()
                .sorted(Comparator
                        .comparing((Benefit benefit) -> favoriteCountMap.getOrDefault(benefit.getBenefitId(), 0L))
                        .reversed()
                        .thenComparing(benefit -> benefit.getBenefitId() == null ? Long.MAX_VALUE : benefit.getBenefitId()))
                .toList();
    }

    private String normalizedBenefitName(Benefit benefit) {
        if (benefit == null || benefit.getBenefitName() == null) {
            return "";
        }
        return benefit.getBenefitName().toLowerCase(java.util.Locale.ROOT);
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
                ? CARRIER_DISPLAY_ORDER
                : carrierFilters;
        Map<Long, List<Benefit>> siblingsByPartnerId = benefitRepository
                .findActiveBenefitsByPartnerIdsWithPartner(partnerIds, mainCategory, category, filter, effectiveCarriers)
                .stream()
                .collect(Collectors.groupingBy(
                        benefit -> benefit.getPartner().getPartnerId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Set<Long> eligibleIds = siblingsByPartnerId.values().stream().flatMap(List::stream)
                .map(Benefit::getBenefitId).collect(Collectors.toSet());
        Map<Long, Benefit> expanded = new LinkedHashMap<>();
        for (Benefit rankedBenefit : rankedBenefits) {
            if (eligibleIds.contains(rankedBenefit.getBenefitId())) {
                expanded.putIfAbsent(rankedBenefit.getBenefitId(), rankedBenefit);
            }
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
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
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

    private record BenefitPage<T>(
            List<T> content,
            int currentPage,
            int totalPages,
            long totalElements,
            boolean hasNext
    ) {
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
        return getBenefitDetail(benefitId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public BenefitDetailResponse getBenefitDetail(Long benefitId, Long userId) {
        Benefit benefit = benefitRepository.findBenefitWithPartnerById(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        List<BenefitCarrierPolicy> policies = findBenefitPolicies(benefit);
        BenefitCarrierPolicy policy = BenefitPolicySelector.eligible(policies, List.of(), null).stream()
                .findFirst().orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        Set<Long> favoriteIds = favoriteBenefitIds(userId, List.of(benefitId));
        Map<Long, List<CarrierTierBenefit>> tiersByPolicy = tierBenefitsByPolicy(policies);

        return BenefitDetailResponse.builder()
                .benefitId(benefit.getBenefitId())
                .benefitName(policy == null ? benefit.getBenefitName() : policy.getDisplayBenefitName())
                .description(policy.getConditions() != null && policy.getConditions().offerResolution() != null
                        ? null : policy.getDescription())
                .benefitLimit(policy.getDisplayBenefitLimit())
                .manual(trimNullable(policy.getManual()))
                .url(policy.getPublicUrl())
                .sourceUrl(trimNullable(policy.getSourceUrl()))
                .sourceCapturedAt(policy.getSourceCapturedAt())
                .conditions(policy.getConditions() == null ? null : policy.getConditions().publicView())
                .carrier(policy.getCarrier())
                .usageType(policy.getUsageType())
                .active(!Boolean.FALSE.equals(policy.getActive()))
                .partnerName(benefit.getPartner().getPartnerName())
                .image(benefit.getPartner().getImage())
                .mainCategory(benefit.getMainCategory())
                .isFavorite(favoriteIds.contains(benefitId))
                .tierBenefits(toPolicyTierBenefits(policy, tiersByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of())))
                .policies(toPolicyDetails(policies, favoriteIds, tiersByPolicy))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public MapBenefitDetailResponse getMapBenefitDetail(Long storeId, Long partnerId, MainCategory mainCategory,
                                                        Carrier carrier, Long userId) {
        // Store + Partner 체크
        Store store = storeRepository.findByIdAndPartnerId(storeId, partnerId)
                .orElseThrow(() -> new StorePartnerMismatchException(StoreCode.STORE_PARTNER_MISMATCH));
        if (!StorePartnerBusinessPolicy.matches(store)) {
            throw new StorePartnerMismatchException(StoreCode.STORE_PARTNER_MISMATCH);
        }

        // Benefit + TierBenefit 조회
        List<Benefit> benefits = benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(partnerId, mainCategory);

        if (benefits.isEmpty()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_DETAIL_NOT_FOUND);
        }

        List<BenefitCarrierPolicy> displayPolicies = findDisplayPolicies(benefits);
        List<BenefitCarrierPolicy> carrierPolicies = carrier == null ? displayPolicies : displayPolicies.stream()
                .filter(policy -> policy.getCarrier() == carrier).toList();
        BenefitCarrierPolicy selectedPolicy = selectRepresentativePolicy(carrierPolicies, carrier, userId);
        if (selectedPolicy == null) {
            throw new BenefitOfflineNotFoundException(BenefitCode.BENEFIT_OFFLINE_NOT_FOUND);
        }
        Benefit selected = selectedPolicy.getBenefit();

        Set<Long> favoriteIds = favoriteBenefitIds(userId,
                displayPolicies.stream().map(policy -> policy.getBenefit().getBenefitId()).distinct().toList());
        boolean isFavorite = favoriteIds.contains(selected.getBenefitId());

        Map<Long, List<CarrierTierBenefit>> tiersByPolicy = tierBenefitsByPolicy(displayPolicies);
        List<BenefitCarrierPolicy> tierPolicies = carrier == null ? displayPolicies : carrierPolicies;
        List<TierBenefitInfo> tierDtos = toDistinctTierBenefitInfosFromPolicies(tierPolicies, tiersByPolicy);

        return MapBenefitDetailResponse.builder()
                .benefitId(selected.getBenefitId())
                .benefitName(selectedPolicy.getDisplayBenefitName())
                .image(selected.getPartner().getImage())
                .mainCategory(selected.getMainCategory())
                .manual(trimNullable(selectedPolicy.getManual()))
                .url(selectedPolicy.getPublicUrl())
                .sourceUrl(trimNullable(selectedPolicy.getSourceUrl()))
                .sourceCapturedAt(selectedPolicy.getSourceCapturedAt())
                .conditions(selectedPolicy.getConditions() == null ? null : selectedPolicy.getConditions().publicView())
                .carrier(selectedPolicy.getCarrier())
                .usageType(selectedPolicy.getUsageType())
                .tierBenefits(tierDtos)
                .isFavorite(isFavorite)
                .policies(toPolicyDetails(carrierPolicies, favoriteIds, tiersByPolicy))
                .build();
    }

    private List<BenefitCarrierPolicy> findBenefitPolicies(Benefit benefit) {
        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit));
        if (policies == null || policies.isEmpty()) policies = benefit.getCarrierPolicies();
        return BenefitPolicySelector.eligible(policies, List.of(), null);
    }

    private Set<Long> favoriteBenefitIds(Long userId, List<Long> benefitIds) {
        if (userId == null || benefitIds.isEmpty()) return Set.of();
        return new HashSet<>(favoriteRepository.findFavoriteBenefitIdsByUser(userId, benefitIds));
    }

    private Map<Long, List<CarrierTierBenefit>> tierBenefitsByPolicy(List<BenefitCarrierPolicy> policies) {
        if (policies.isEmpty()) return Map.of();
        List<CarrierTierBenefit> tiers = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        if (tiers == null || tiers.isEmpty()) {
            tiers = policies.stream().flatMap(policy -> policy.getTierBenefits().stream()).toList();
        }
        return tiers.stream().collect(Collectors.groupingBy(
                tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
    }

    private List<BenefitPolicyDetail> toPolicyDetails(List<BenefitCarrierPolicy> policies, Set<Long> favoriteIds,
                                                       Map<Long, List<CarrierTierBenefit>> tiersByPolicy) {
        return policies.stream().map(policy -> {
            Benefit benefit = policy.getBenefit();
            return BenefitPolicyDetail.builder()
                    .benefitId(benefit.getBenefitId())
                    .carrier(policy.getCarrier())
                    .benefitName(policy.getDisplayBenefitName())
                    .mainCategory(benefit.getMainCategory())
                    .description(policy.getConditions() != null && policy.getConditions().offerResolution() != null
                            ? null : policy.getDescription())
                    .conditions(policy.getConditions() == null ? null : policy.getConditions().publicView())
                    .benefitLimit(policy.getDisplayBenefitLimit())
                    .manual(trimNullable(policy.getManual()))
                    .url(policy.getPublicUrl())
                    .sourceUrl(trimNullable(policy.getSourceUrl()))
                    .sourceCapturedAt(policy.getSourceCapturedAt())
                    .usageType(policy.getUsageType())
                    .active(!Boolean.FALSE.equals(policy.getActive()))
                    .partnerName(benefit.getPartner() == null ? null : benefit.getPartner().getPartnerName())
                    .image(benefit.getPartner() == null ? null : benefit.getPartner().getImage())
                    .tierBenefits(toPolicyTierBenefits(policy,
                            tiersByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of())))
                    .isFavorite(favoriteIds.contains(benefit.getBenefitId()))
                    .build();
        }).toList();
    }

    private List<TierBenefitInfo> toPolicyTierBenefits(BenefitCarrierPolicy policy, List<CarrierTierBenefit> tiers) {
        Set<String> allGradeContexts = TierBenefitSemantics.allGradeContexts(tiers,
                referenceDataService.allGradeMembers(policy.getCarrier()));
        return tiers.stream().map(tier -> {
            BenefitContextSplitter.SplitContext split = BenefitContextSplitter.split(tier.getContext());
            return new TierBenefitInfo(policy.getCarrier(), tier.getGrade(), tier.getContext(), split.onlineContext(),
                    split.offlineContext(), allGradeContexts.contains(tier.getContext()));
        }).toList();
    }

    private List<BenefitCarrierPolicy> findDisplayPolicies(List<Benefit> benefits) {
        List<BenefitCarrierPolicy> foundPolicies = benefitCarrierPolicyRepository.findAllByBenefitIn(benefits);
        if (foundPolicies == null || foundPolicies.isEmpty()) {
            foundPolicies = benefits.stream()
                    .flatMap(benefit -> benefit.getCarrierPolicies().stream())
                    .toList();
        }

        List<BenefitCarrierPolicy> policies = foundPolicies.stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
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
            return policies.stream().filter(policy -> policy.getCarrier() == requestedCarrier).findFirst().orElse(null);
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

    private List<TierBenefitInfo> toDistinctTierBenefitInfosFromPolicies(
            List<BenefitCarrierPolicy> policies, Map<Long, List<CarrierTierBenefit>> tiersByPolicy) {
        List<CarrierTierBenefit> tiers = policies.stream()
                .flatMap(policy -> tiersByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()).stream())
                .toList();
        Map<Long, BenefitCarrierPolicy> policyById = policies.stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getBenefitCarrierPolicyId, policy -> policy));
        Map<Long, Set<String>> allGradeContextsByPolicy = policies.stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getBenefitCarrierPolicyId,
                        policy -> TierBenefitSemantics.allGradeContexts(tiersByPolicy
                                .getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()),
                                referenceDataService.allGradeMembers(policy.getCarrier()))));
        Map<String, TierBenefitInfo> distinct = new LinkedHashMap<>();
        tiers.forEach(tierBenefit -> {
            BenefitCarrierPolicy policy = policyById.get(tierBenefit.getBenefitCarrierPolicy().getBenefitCarrierPolicyId());
            if (policy == null) {
                return;
            }
            BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(tierBenefit.getContext());
            TierBenefitInfo info = new TierBenefitInfo(
                    policy.getCarrier(),
                    tierBenefit.getGrade(),
                    tierBenefit.getContext(),
                    splitContext.onlineContext(),
                    splitContext.offlineContext(),
                    allGradeContextsByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), Set.of())
                            .contains(tierBenefit.getContext())
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
        if (MainCategory.BASIC_BENEFIT.equals(mainCategory)) {
            return 0;
        }
        if (MainCategory.VIP_COCK.equals(mainCategory)) {
            return 1;
        }
        return 2;
    }

    private void validateReferenceFilters(MainCategory mainCategory, String category) {
        if (mainCategory != null && !referenceDataService.isActiveProgram(mainCategory.getCode())) {
            throw new com.itplace.userapi.benefit.exception.InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
        }
        if (org.springframework.util.StringUtils.hasText(category) && !referenceDataService.isActiveCategory(category.trim())) {
            throw new com.itplace.userapi.benefit.exception.InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
        }
    }

    private String trimNullable(String value) {
        return value == null ? null : value.trim();
    }

    private BenefitCarrierPolicy selectListPolicy(List<BenefitCarrierPolicy> policies, List<Carrier> carriers, UsageType usageType) {
        return BenefitPolicySelector.eligible(policies, carriers, usageType).stream().findFirst().orElse(null);
    }

}
