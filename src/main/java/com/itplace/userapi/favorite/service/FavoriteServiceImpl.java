package com.itplace.userapi.favorite.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.BenefitPreview;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.PreviewTierBenefit;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.service.TierBenefitSemantics;
import com.itplace.userapi.benefit.service.BenefitPreviewSummaryBuilder;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.benefit.support.BenefitPolicySelector;
import com.itplace.userapi.favorite.FavoriteCode;
import com.itplace.userapi.favorite.dto.TierBenefitDetail;
import com.itplace.userapi.favorite.dto.response.FavoriteDetailResponse.CarrierPolicyDetail;
import com.itplace.userapi.favorite.dto.response.FavoriteDetailResponse;
import com.itplace.userapi.favorite.dto.response.FavoriteResponse;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.exception.DuplicateFavoriteException;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class FavoriteServiceImpl implements FavoriteService {
    private final ReferenceDataService referenceDataService;

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final BenefitPreviewSummaryBuilder previewSummaryBuilder;
    private final LogService logService;

    @Override
    public void addFavorite(Long userId, Long benefitId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        Benefit benefit = benefitRepository.findAllByIdWithPartner(List.of(benefitId)).stream()
                .findFirst()
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));

        if (favoriteRepository.existsByUserAndBenefit(user, benefit)) {
            throw new DuplicateFavoriteException(FavoriteCode.FAVORITE_ALREADY_EXISTS);
        }

        Favorite favorite = Favorite.builder()
                .user(user)
                .benefit(benefit)
                .build();

        favoriteRepository.save(favorite);
        logAfterCommit(userId, List.of(toFavoriteLogCommand("favorite_add", benefit)));
    }

    @Override
    public void removeFavorites(Long userId, List<Long> benefitIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Long> uniqueIds = benefitIds.stream().distinct().sorted().toList();
        if (uniqueIds.isEmpty()) {
            return;
        }
        List<Benefit> benefits = benefitRepository.findAllByIdWithPartner(uniqueIds);
        if (benefits.size() != uniqueIds.size()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND);
        }
        List<Favorite> favorites = favoriteRepository.findForRemoval(user, uniqueIds);
        if (favorites.isEmpty()) {
            return;
        }
        List<ResponseLogCommand> commands = favorites.stream()
                .map(favorite -> toFavoriteLogCommand("favorite_remove", favorite.getBenefit())).toList();
        favoriteRepository.deleteAll(favorites);
        logAfterCommit(userId, commands);
    }

    private void logAfterCommit(Long userId, List<ResponseLogCommand> commands) {
        Runnable submit = () -> {
            try {
                logService.saveResponseLogs(userId, commands);
            } catch (RuntimeException exception) {
                log.warn("관심 혜택 로그 제출 실패: userId={}", userId, exception);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private ResponseLogCommand toFavoriteLogCommand(String event, Benefit benefit) {
        return new ResponseLogCommand(
                event,
                benefit.getBenefitId(),
                benefit.getPartner() == null ? null : benefit.getPartner().getPartnerId(),
                "/api/v1/favorites",
                "benefitId=" + benefit.getBenefitId()
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<FavoriteResponse> getFavorites(Long userId, String category, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        Page<Favorite> favorites;

        if (category != null && !category.isBlank()) {
            MainCategory mainCategory = MainCategory.fromLabel(category);
            favorites = favoriteRepository.findPageByUserAndCategoryWithBenefitAndPartner(
                    user,
                    mainCategory,
                    pageable
            );
        } else {
            favorites = favoriteRepository.findPageByUserWithBenefitAndPartner(user, pageable);
        }

        Map<Long, List<BenefitPreview>> previewsByBenefit = loadFavoritePreviews(favorites.getContent());
        return favorites.map(fav -> {
            Benefit benefit = fav.getBenefit();
            Partner partner = benefit.getPartner();

            return FavoriteResponse.builder()
                    .benefitId(benefit.getBenefitId())
                    .benefitName(favoriteName(benefit, previewsByBenefit))
                    .partnerName(partner != null ? partner.getPartnerName() : null)
                    .partnerImage(partner != null ? partner.getImage() : null)
                    .previews(previewsByBenefit.getOrDefault(benefit.getBenefitId(), List.of()))
                    .build();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<FavoriteResponse> searchFavorites(
            Long userId,
            String keyword,
            String category
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Favorite> favs;
        // category가 전체(또는 null)이면 전체 조회
        if (category == null || category.equals("전체")) {
            favs = favoriteRepository
                    .findByUserAndBenefit_BenefitNameContaining(user, keyword);
        }
        // 특정 카테고리일 때만 partner.category 필터 적용
        else {
            favs = favoriteRepository.searchByUserKeywordAndPartnerCategoryWithBenefitAndPartner(
                    user,
                    keyword,
                    category
            );
        }

        Map<Long, List<BenefitPreview>> previewsByBenefit = loadFavoritePreviews(favs);
        return favs.stream()
                .map(fav -> {
                    Benefit b = fav.getBenefit();
                    Partner p = b.getPartner();
                    return FavoriteResponse.builder()
                            .benefitId(b.getBenefitId())
                            .benefitName(favoriteName(b, previewsByBenefit))
                            .partnerName(p != null ? p.getPartnerName() : null)
                            .partnerImage(p != null ? p.getImage() : null)
                            .previews(previewsByBenefit.getOrDefault(b.getBenefitId(), List.of()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private Map<Long, List<BenefitPreview>> loadFavoritePreviews(List<Favorite> favorites) {
        List<Benefit> benefits = favorites.stream()
                .map(Favorite::getBenefit)
                .distinct()
                .toList();
        if (benefits.isEmpty()) {
            return Map.of();
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository
                .findAllForFavoritePreviews(benefits);
        policies = BenefitPolicySelector.eligible(policies, List.of(), null);
        if (policies.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<CarrierTierBenefit>> tierRowsByPolicy = carrierTierBenefitRepository
                .findAllByBenefitCarrierPolicyIn(policies).stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));

        return policies.stream()
                .collect(Collectors.groupingBy(
                        policy -> policy.getBenefit().getBenefitId(),
                        Collectors.mapping(policy -> new BenefitPreview(
                                policy.getCarrier(),
                                policy.getBenefit().getBenefitId(),
                                policy.getDisplayBenefitName(),
                                policy.getDisplayBenefitLimit(),
                                policy.getUsageType(),
                                tierRowsByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()).stream()
                                        .map(tier -> new PreviewTierBenefit(tier.getGrade(), tier.getContext(), tier.getIsAll())).toList(),
                                previewSummaryBuilder.build(policy.getConditions(),
                                        tierRowsByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()),
                                        policy.getCarrier(), List.of()),
                                List.of(), null, null, List.of(), List.of(), null, null
                        ),
                                Collectors.collectingAndThen(Collectors.toList(), List::copyOf))
                ));
    }

    private String favoriteName(Benefit benefit, Map<Long, List<BenefitPreview>> previewsByBenefit) {
        List<BenefitPreview> previews = previewsByBenefit.getOrDefault(benefit.getBenefitId(), List.of());
        return previews.isEmpty() ? benefit.getBenefitName() : previews.get(0).benefitName();
    }


    @Override
    @Transactional(readOnly = true)
    public FavoriteDetailResponse getBenefitDetail(Long benefitId) {
        Benefit benefit = benefitRepository.findDetailById(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        List<BenefitCarrierPolicy> policies = BenefitPolicySelector.eligible(
                benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)), List.of(), null);
        if (policies.isEmpty()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND);
        }

        BenefitCarrierPolicy primaryPolicy = policies.get(0);
        Partner partner = benefit.getPartner();

        List<CarrierTierBenefit> carrierTiers = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        Map<Long, Set<String>> allGradeContextsByPolicy = new java.util.HashMap<>();
        for (BenefitCarrierPolicy policy : policies) {
            List<CarrierTierBenefit> policyTiers = carrierTiers.stream()
                    .filter(tier -> tier.getBenefitCarrierPolicy() == policy
                            || (policy.getBenefitCarrierPolicyId() != null
                            && policy.getBenefitCarrierPolicyId().equals(
                            tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId())))
                    .toList();
            allGradeContextsByPolicy.put(policy.getBenefitCarrierPolicyId(),
                    TierBenefitSemantics.allGradeContexts(policyTiers,
                            referenceDataService.allGradeMembers(policy.getCarrier())));
        }
        List<TierBenefitDetail> tierDtos = carrierTiers.stream()
                .map(tb -> TierBenefitDetail.builder()
                        .carrier(tb.getBenefitCarrierPolicy().getCarrier())
                        .grade(tb.getGrade())
                        .isAll(allGradeContextsByPolicy.getOrDefault(
                                tb.getBenefitCarrierPolicy().getBenefitCarrierPolicyId(), Set.of())
                                .contains(tb.getContext()))
                        .context(tb.getContext())
                        .discountValue(tb.getDiscountValue())
                        .build())
                .collect(Collectors.toList());

        List<CarrierPolicyDetail> carrierPolicies = policies.stream()
                .map(policy -> new CarrierPolicyDetail(
                        policy.getCarrier(),
                        policy.getDescription(),
                        policy.getDisplayBenefitLimit(),
                        policy.getSourceUrl(),
                        policy.getSourceCapturedAt(),
                        policy.getManual(),
                        policy.getConditions() == null ? null : policy.getConditions().publicView()))
                .toList();

        return FavoriteDetailResponse.builder()
                .benefitId(benefit.getBenefitId())
                .benefitName(primaryPolicy.getDisplayBenefitName())
                .benefitDescription(primaryPolicy.getConditions() != null
                        && primaryPolicy.getConditions().offerResolution() != null
                        ? null : primaryPolicy.getDescription())
                .benefitLimit(primaryPolicy.getDisplayBenefitLimit())
                .partnerName(partner != null ? partner.getPartnerName() : null)
                .partnerImage(partner != null ? partner.getImage() : null)
                .partnerId(partner != null ? partner.getPartnerId() : null)
                .tiers(tierDtos)
                .carrierPolicies(carrierPolicies)
                .build();
    }


}
