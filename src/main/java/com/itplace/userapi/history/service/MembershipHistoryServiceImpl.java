package com.itplace.userapi.history.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.PagedResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.entity.TierBenefitId;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.history.MembershipHistoryCode;
import com.itplace.userapi.history.dto.MembershipHistoryResponse;
import com.itplace.userapi.history.dto.MonthlyDiscountResponse;
import com.itplace.userapi.history.entity.MembershipHistory;
import com.itplace.userapi.history.exception.InvalidBenefitUsageException;
import com.itplace.userapi.history.repository.MembershipHistoryRepository;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StoreNotFoundException;
import com.itplace.userapi.map.exception.StorePartnerMismatchException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.entity.Membership;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.MembershipNotFoundException;
import com.itplace.userapi.user.exception.NoMembershipException;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipHistoryServiceImpl implements MembershipHistoryService {

    private final UserRepository userRepository;
    private final MembershipHistoryRepository historyRepository;
    private final MembershipRepository membershipRepository;
    private final BenefitRepository benefitRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final StoreRepository storeRepository;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<MembershipHistoryResponse> getUserHistory(
            Long userId,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        String membershipId = user.getMembershipId();
        if (membershipId == null) {
            throw new NoMembershipException(UserCode.NO_MEMBERSHIP);
        }

        Page<MembershipHistory> page = historyRepository.findFiltered(
                membershipId,
                keyword,
                startDate,
                endDate,
                pageable
        );

        List<MembershipHistoryResponse> dtoList = page.stream()
                .map(mh -> {
                    Benefit b = mh.getBenefit();
                    Partner p = b.getPartner();
                    return MembershipHistoryResponse.builder()
                            .image(p.getImage())
                            .benefitName(b.getBenefitName())
                            .discountAmount(mh.getDiscountAmount())
                            .usedAt(mh.getUsedAt())
                            .build();
                })
                .toList();

        return new PagedResponse<>(
                dtoList,
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.hasNext()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public MonthlyDiscountResponse getMonthlyDiscountSummary(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        String membershipId = user.getMembershipId();
        if (membershipId == null) {
            throw new NoMembershipException(UserCode.NO_MEMBERSHIP);
        }

        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();

        Long totalDiscountAmount = historyRepository.sumDiscountAmountThisMonth(membershipId, year, month);

        return MonthlyDiscountResponse.builder()
                .userId(userId)
                .yearMonth(String.format("%04d-%02d", year, month))
                .totalDiscountAmount(totalDiscountAmount)
                .build();
    }

    @Transactional
    @Override
    public void useMembership(Long userId, Long benefitId, Integer amount, Long storeId) {
        // 사용자 확인
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        // 멤버십 확인
        String membershipId = user.getMembershipId();
        if (membershipId == null) {
            throw new NoMembershipException(UserCode.NO_MEMBERSHIP);
        }

        Membership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new MembershipNotFoundException(UserCode.MEMBERSHIP_NOT_FOUND));

        Grade membershipGrade = membership.getGrade();

        // 혜택 조회
        Benefit benefit = benefitRepository.findByIdWithPolicy(benefitId)
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));

        // 등급별 사용 가능 여부 (BASIC은 VIP_COCK 사용 불가)
        if (membershipGrade == Grade.BASIC && benefit.getMainCategory() == MainCategory.VIP_COCK) {
            throw new InvalidBenefitUsageException(BenefitCode.INVALID_GRADE_FOR_BENEFIT);
        }

        // tierBenefit 존재 여부 체크
        TierBenefit tierBenefit = tierBenefitRepository.findById(new TierBenefitId(membershipGrade, benefitId))
                .orElseGet(() -> {
                    // VIP/VVIP인데 tierBenefit.grade가 VIP콕일 때 조회
                    if ((membershipGrade == Grade.VIP || membershipGrade == Grade.VVIP)
                            && benefit.getMainCategory() == MainCategory.VIP_COCK) {
                        return tierBenefitRepository.findById(new TierBenefitId(Grade.VIP콕, benefitId))
                                .orElseThrow(
                                        () -> new InvalidBenefitUsageException(BenefitCode.TIER_BENEFIT_NOT_FOUND));
                    }
                    throw new InvalidBenefitUsageException(BenefitCode.TIER_BENEFIT_NOT_FOUND);
                });

        // 사용 제한 체크 (BenefitPolicy)
        validateBenefitLimit(membership, benefit);

        // 할인 금액 계산
        Long discountAmount = calculateDiscountAmount(tierBenefit, benefit, amount);

        // partnerId 검증
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(StoreCode.STORE_NOT_FOUND));

        if (!store.getPartner().getPartnerId().equals(benefit.getPartner().getPartnerId())) {
            throw new StorePartnerMismatchException(StoreCode.STORE_PARTNER_MISMATCH);
        }

        // 사용 기록 저장
        MembershipHistory history = MembershipHistory.builder()
                .membership(membership)
                .benefit(benefit)
                .usedAt(LocalDateTime.now())
                .discountAmount(discountAmount)
                .build();

        historyRepository.save(history);

        // 쿠폰 지급
        if (store.isHasCoupon()) {
            user.setCoupon(user.getCoupon() + 1);
        }
    }

    private void validateBenefitLimit(Membership membership, Benefit benefit) {
        BenefitPolicy policy = benefit.getBenefitPolicy();
        LocalDateTime now = LocalDateTime.now();

        switch (policy.getCode()) {
            case MONTHLY_ONCE:
                LocalDateTime startOfMonth = now.toLocalDate()
                        .withDayOfMonth(1)
                        .atStartOfDay();
                LocalDateTime endOfMonth = now.toLocalDate()
                        .withDayOfMonth(now.toLocalDate().lengthOfMonth())
                        .atTime(LocalTime.MAX);

                if (historyRepository.existsByMembershipAndBenefitAndUsedAtBetween(
                        membership, benefit, startOfMonth, endOfMonth)) {
                    throw new InvalidBenefitUsageException(MembershipHistoryCode.ALREADY_USED_THIS_MONTH);
                }
                break;
            case DAILY_ONCE:
                LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
                LocalDateTime endOfDay = now.toLocalDate().atTime(LocalTime.MAX);

                if (historyRepository.existsByMembershipAndBenefitAndUsedAtBetween(
                        membership, benefit, startOfDay, endOfDay)) {
                    throw new InvalidBenefitUsageException(MembershipHistoryCode.ALREADY_USED_TODAY);
                }
                break;
            case ONCE:
                if (historyRepository.existsByMembershipAndBenefit(membership, benefit)) {
                    throw new InvalidBenefitUsageException(MembershipHistoryCode.ALREADY_USED_ONCE);
                }
                break;
            case UNLIMITED:
                break;
        }
    }

    private long calculateDiscountAmount(TierBenefit tierBenefit, Benefit benefit, Integer amount) {
        if (benefit.getType() == BenefitType.FREE) {
            return tierBenefit.getDiscountValue();
        } else if (benefit.getType() == BenefitType.DISCOUNT) {
            if (amount == null || amount <= 0) {
                throw new InvalidBenefitUsageException(MembershipHistoryCode.AMOUNT_REQUIRED);
            }
            return Math.round(amount * (tierBenefit.getDiscountValue() / 100.0));
        }

        throw new InvalidBenefitUsageException(BenefitCode.INVALID_BENEFIT_TYPE);
    }
}

