package com.itplace.userapi.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.history.entity.MembershipHistory;
import com.itplace.userapi.history.repository.MembershipHistoryRepository;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StorePartnerMismatchException;
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
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

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
                .carrier(com.itplace.userapi.benefit.entity.enums.Carrier.SKT)
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
                .build();
        BenefitCarrierPolicy policy = policy(benefit, BenefitType.FREE);
        CarrierTierBenefit tierBenefit = carrierTier(policy, Grade.BASIC, 1000, "무료");
        Store store = Store.builder()
                .storeId(30L)
                .partner(partner)
                .hasCoupon(true)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(membershipRepository.findById("membership-1")).thenReturn(Optional.of(membership));
        when(benefitRepository.findById(20L)).thenReturn(Optional.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(java.util.List.of(benefit))).thenReturn(java.util.List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicy(policy)).thenReturn(java.util.List.of(tierBenefit));
        when(storeRepository.findById(30L)).thenReturn(Optional.of(store));

        membershipHistoryService.useMembership(1L, 20L, null, 30L);

        assertThat(user.getCoupon()).isEqualTo(2);
        verify(historyRepository).save(any(MembershipHistory.class));
    }

    @Test
    void useMembership_rejectsSeoulLandBenefitForFancyLandStoreWhenPartnerDoesNotMatch() {
        User user = User.builder()
                .id(1L)
                .membershipId("membership-1")
                .carrier(com.itplace.userapi.benefit.entity.enums.Carrier.SKT)
                .role(Role.USER)
                .build();
        Membership membership = Membership.builder()
                .membershipId("membership-1")
                .grade(Grade.BASIC)
                .build();
        Partner seoulLand = Partner.builder()
                .partnerId(100L)
                .partnerName("서울랜드")
                .build();
        Partner fancyLand = Partner.builder()
                .partnerId(200L)
                .partnerName("Fancy Land 문구점")
                .build();
        Benefit seoulLandBenefit = Benefit.builder()
                .benefitId(20L)
                .partner(seoulLand)
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .build();
        BenefitCarrierPolicy policy = policy(seoulLandBenefit, BenefitType.FREE);
        CarrierTierBenefit tierBenefit = carrierTier(policy, Grade.BASIC, 1000, "서울랜드 무료");
        Store fancyLandStore = Store.builder()
                .storeId(30L)
                .partner(fancyLand)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(membershipRepository.findById("membership-1")).thenReturn(Optional.of(membership));
        when(benefitRepository.findById(20L)).thenReturn(Optional.of(seoulLandBenefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(java.util.List.of(seoulLandBenefit))).thenReturn(java.util.List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicy(policy)).thenReturn(java.util.List.of(tierBenefit));
        when(storeRepository.findById(30L)).thenReturn(Optional.of(fancyLandStore));

        assertThatThrownBy(() -> membershipHistoryService.useMembership(1L, 20L, null, 30L))
                .isInstanceOf(StorePartnerMismatchException.class)
                .hasMessage("지점과 제휴사가 일치하지 않습니다.");
        verify(historyRepository, never()).save(any(MembershipHistory.class));
    }

    private BenefitCarrierPolicy policy(Benefit benefit, BenefitType type) {
        return BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .carrier(com.itplace.userapi.benefit.entity.enums.Carrier.SKT)
                .type(type)
                .benefitPolicy(BenefitPolicy.builder()
                        .code(BenefitPolicyCode.UNLIMITED)
                        .name("무제한")
                        .build())
                .build();
    }

    private CarrierTierBenefit carrierTier(BenefitCarrierPolicy policy, Grade grade, Integer discountValue, String context) {
        return CarrierTierBenefit.builder()
                .benefitCarrierPolicy(policy)
                .grade(grade)
                .discountValue(discountValue)
                .context(context)
                .build();
    }
}
