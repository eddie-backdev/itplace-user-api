package com.itplace.userapi.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.entity.TierBenefitId;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.history.entity.MembershipHistory;
import com.itplace.userapi.history.repository.MembershipHistoryRepository;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.user.entity.Membership;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MembershipHistoryServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MembershipHistoryRepository historyRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private TierBenefitRepository tierBenefitRepository;

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private MembershipHistoryServiceImpl membershipHistoryService;

    @Test
    void useMembership_doesNotGrantRetiredEventCouponForCouponStore() {
        User user = User.builder()
                .id(1L)
                .membershipId("membership-1")
                .coupon(2)
                .role(Role.USER)
                .build();
        Membership membership = Membership.builder()
                .membershipId("membership-1")
                .grade(Grade.BASIC)
                .build();
        Partner partner = Partner.builder()
                .partnerId(10L)
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(20L)
                .partner(partner)
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .type(BenefitType.FREE)
                .benefitPolicy(BenefitPolicy.builder()
                        .code(BenefitPolicyCode.UNLIMITED)
                        .name("무제한")
                        .build())
                .build();
        TierBenefit tierBenefit = TierBenefit.builder()
                .grade(Grade.BASIC)
                .benefit(benefit)
                .discountValue(1000)
                .context("무료")
                .build();
        Store store = Store.builder()
                .storeId(30L)
                .partner(partner)
                .hasCoupon(true)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(membershipRepository.findById("membership-1")).thenReturn(Optional.of(membership));
        when(benefitRepository.findByIdWithPolicy(20L)).thenReturn(Optional.of(benefit));
        when(tierBenefitRepository.findById(new TierBenefitId(Grade.BASIC, 20L)))
                .thenReturn(Optional.of(tierBenefit));
        when(storeRepository.findById(30L)).thenReturn(Optional.of(store));

        membershipHistoryService.useMembership(1L, 20L, null, 30L);

        assertThat(user.getCoupon()).isEqualTo(2);
        verify(historyRepository).save(any(MembershipHistory.class));
    }
}
