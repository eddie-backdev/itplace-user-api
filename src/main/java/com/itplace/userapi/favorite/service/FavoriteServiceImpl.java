package com.itplace.userapi.favorite.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.dto.response.FavoriteDetailResponse;
import com.itplace.userapi.favorite.dto.response.FavoriteResponse;
import com.itplace.userapi.favorite.dto.TierBenefitDetail;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.FavoriteCode;
import com.itplace.userapi.favorite.exception.DuplicateFavoriteException;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final LogService logService;

    @Override
    public void addFavorite(Long userId, Long benefitId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        Benefit benefit = benefitRepository.findById(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));

        if (favoriteRepository.existsByUserAndBenefit(user, benefit)) {
            throw new DuplicateFavoriteException(FavoriteCode.FAVORITE_ALREADY_EXISTS);
        }

        Favorite favorite = Favorite.builder()
                .user(user)
                .benefit(benefit)
                .build();

        favoriteRepository.save(favorite);
        logService.saveResponseLog(
                userId,
                "favorite_add",
                benefitId,
                benefit.getPartner() == null ? null : benefit.getPartner().getPartnerId(),
                "/api/v1/favorites",
                "benefitId=" + benefitId
        );
    }

    @Override
    public void removeFavorites(Long userId, List<Long> benefitIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Benefit> benefits = benefitRepository.findAllById(benefitIds);

        if (benefits.size() != benefitIds.size()) {
            throw new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND);
        }

        benefits.forEach(benefit -> logService.saveResponseLog(
                userId,
                "favorite_remove",
                benefit.getBenefitId(),
                benefit.getPartner() == null ? null : benefit.getPartner().getPartnerId(),
                "/api/v1/favorites",
                "benefitId=" + benefit.getBenefitId()
        ));
        favoriteRepository.deleteByUserAndBenefitIn(user, benefits);
    }


    @Override
    @Transactional(readOnly = true)
    public Page<FavoriteResponse> getFavorites(Long userId, String category, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        Page<Favorite> favorites;

        if (category != null && !category.isBlank()) {
            MainCategory mainCategory = MainCategory.fromLabel(category);
            favorites = favoriteRepository.findByUserAndBenefit_MainCategory(user, mainCategory, pageable);
        } else {
            favorites = favoriteRepository.findByUser(user, pageable);
        }

        return favorites.map(fav -> {
            Benefit benefit = fav.getBenefit();
            Partner partner = benefit.getPartner();

            return FavoriteResponse.builder()
                    .benefitId(benefit.getBenefitId())
                    .benefitName(benefit.getBenefitName())
                    .partnerName(partner != null ? partner.getPartnerName() : null)
                    .partnerImage(partner != null ? partner.getImage() : null)
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
            favs = favoriteRepository
                    .findByUserAndBenefit_BenefitNameContainingAndBenefit_Partner_CategoryContaining(
                            user, keyword, category);
        }

        return favs.stream()
                .map(fav -> {
                    Benefit b = fav.getBenefit();
                    Partner p = b.getPartner();
                    return FavoriteResponse.builder()
                            .benefitId(b.getBenefitId())
                            .benefitName(b.getBenefitName())
                            .partnerName(p != null ? p.getPartnerName() : null)
                            .partnerImage(p != null ? p.getImage() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }


    @Override
    @Transactional(readOnly = true)
    public FavoriteDetailResponse getBenefitDetail(Long benefitId) {
        Benefit benefit = benefitRepository.findDetailById(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        BenefitCarrierPolicy policy = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)).stream()
                .findFirst()
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));

        Partner partner = benefit.getPartner();

        List<TierBenefitDetail> tierDtos = carrierTierBenefitRepository.findAllByBenefitCarrierPolicy(policy).stream()
                .map(tb -> TierBenefitDetail.builder()
                        .grade(tb.getGrade())
                        .isAll(tb.getIsAll())
                        .context(tb.getContext())
                        .discountValue(tb.getDiscountValue())
                        .build())
                .collect(Collectors.toList());

        return FavoriteDetailResponse.builder()
                .benefitId(benefit.getBenefitId())
                .benefitName(benefit.getBenefitName())
                .benefitDescription(policy.getDescription())
                .benefitLimit(policy.getBenefitPolicy() == null ? null : policy.getBenefitPolicy().getName())
                .partnerName(partner != null ? partner.getPartnerName() : null)
                .partnerImage(partner != null ? partner.getImage() : null)
                .tiers(tierDtos)
                .build();
    }


}
