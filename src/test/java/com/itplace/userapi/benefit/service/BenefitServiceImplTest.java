package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.MapBenefitDetailResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StorePartnerMismatchException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitServiceImplTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BenefitServiceImpl benefitService;

    @Test
    void getBenefitDetail_handlesMissingManualAndUrlWithoutNullPointerException() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파트너")
                .image("image")
                .build();
        Benefit benefit = Benefit.builder()
                .benefitId(1L)
                .benefitName("혜택")
                .partner(partner)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(1L)
                .benefit(benefit)
                .carrier(Carrier.SKT)
                .description("설명")
                .manual(null)
                .url(null)
                .benefitPolicy(BenefitPolicy.builder().name("월 1회").build())
                .active(true)
                .build();
        benefit.setCarrierPolicies(List.of(policy));

        when(benefitRepository.findBenefitWithPartnerById(1L)).thenReturn(Optional.of(benefit));

        BenefitDetailResponse response = benefitService.getBenefitDetail(1L);

        assertThat(response.getManual()).isNull();
        assertThat(response.getUrl()).isNull();
        assertThat(response.getPartnerName()).isEqualTo("파트너");
    }

    @Test
    void getMapBenefitDetail_returnsAllCarrierTierBenefitsAndUsesUserCarrierAsRepresentative() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파스쿠찌")
                .image("image")
                .build();
        Store store = Store.builder()
                .storeId(20L)
                .partner(partner)
                .build();
        Benefit skt = benefit(1L, partner, Carrier.SKT, "SKT 오프라인 혜택", Grade.SKT_GOLD, "SKT 골드 할인");
        Benefit kt = benefit(2L, partner, Carrier.KT, "KT 오프라인 혜택", Grade.KT_VIP, "KT VIP 할인");
        Benefit lgu = benefit(3L, partner, Carrier.LGU, "LGU 오프라인 혜택", Grade.VIP, "LGU VIP 할인");
        User user = User.builder()
                .id(99L)
                .carrier(Carrier.KT)
                .build();

        when(storeRepository.findByIdAndPartnerId(20L, 10L)).thenReturn(Optional.of(store));
        when(benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(10L, MainCategory.BASIC_BENEFIT))
                .thenReturn(List.of(lgu, skt, kt));
        when(userRepository.findById(99L)).thenReturn(Optional.of(user));
        when(favoriteRepository.existsByUser_IdAndBenefit_BenefitId(99L, 2L)).thenReturn(true);

        MapBenefitDetailResponse response = benefitService.getMapBenefitDetail(
                20L, 10L, MainCategory.BASIC_BENEFIT, null, 99L);

        assertThat(response.getBenefitId()).isEqualTo(2L);
        assertThat(response.getImage()).isEqualTo("image");
        assertThat(response.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(response.getIsFavorite()).isTrue();
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.KT, Carrier.LGU);
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getContext())
                .containsExactly("SKT 골드 할인", "KT VIP 할인", "LGU VIP 할인");
    }

    @Test
    void getMapBenefitDetail_usesRequestedCarrierBeforeUserCarrier() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파스쿠찌")
                .image("image")
                .build();
        Store store = Store.builder()
                .storeId(20L)
                .partner(partner)
                .build();
        Benefit skt = benefit(1L, partner, Carrier.SKT, "SKT 오프라인 혜택", Grade.SKT_GOLD, "SKT 골드 할인");
        Benefit kt = benefit(2L, partner, Carrier.KT, "KT 오프라인 혜택", Grade.KT_VIP, "KT VIP 할인");

        when(storeRepository.findByIdAndPartnerId(20L, 10L)).thenReturn(Optional.of(store));
        when(benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(10L, MainCategory.BASIC_BENEFIT))
                .thenReturn(List.of(kt, skt));
        when(favoriteRepository.existsByUser_IdAndBenefit_BenefitId(99L, 1L)).thenReturn(false);

        MapBenefitDetailResponse response = benefitService.getMapBenefitDetail(
                20L, 10L, MainCategory.BASIC_BENEFIT, Carrier.SKT, 99L);

        assertThat(response.getBenefitId()).isEqualTo(1L);
        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.KT);
    }

    @Test
    void getMapBenefitDetail_prefersNormalizedCarrierPoliciesWhenPresent() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("GS25")
                .image("image")
                .build();
        Store store = Store.builder()
                .storeId(20L)
                .partner(partner)
                .build();
        Benefit master = Benefit.builder()
                .benefitId(1L)
                .partner(partner)
                .benefitName("GS25 할인")
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .build();
        BenefitCarrierPolicy sktPolicy = policy(11L, master, Carrier.SKT, "SKT 사용법", "https://skt.example");
        BenefitCarrierPolicy ktPolicy = policy(12L, master, Carrier.KT, "KT 사용법", "https://kt.example");

        when(storeRepository.findByIdAndPartnerId(20L, 10L)).thenReturn(Optional.of(store));
        when(benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(10L, MainCategory.BASIC_BENEFIT))
                .thenReturn(List.of(master));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(master)))
                .thenReturn(List.of(sktPolicy, ktPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(sktPolicy, ktPolicy)))
                .thenReturn(List.of(
                        carrierTier(sktPolicy, Grade.SKT_GOLD, "SKT 골드 할인"),
                        carrierTier(ktPolicy, Grade.KT_VIP, "KT VIP 할인")
                ));

        MapBenefitDetailResponse response = benefitService.getMapBenefitDetail(
                20L, 10L, MainCategory.BASIC_BENEFIT, Carrier.KT, null);

        assertThat(response.getBenefitId()).isEqualTo(1L);
        assertThat(response.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(response.getManual()).isEqualTo("KT 사용법");
        assertThat(response.getUrl()).isEqualTo("https://kt.example");
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.KT);
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getContext())
                .containsExactly("SKT 골드 할인", "KT VIP 할인");
    }

    @Test
    void getMapBenefitDetail_withoutMainCategoryReturnsOfflineBenefitsAcrossCategories() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("파스쿠찌")
                .image("image")
                .build();
        Store store = Store.builder()
                .storeId(20L)
                .partner(partner)
                .build();
        Benefit sktBasic = benefit(
                1L, partner, Carrier.SKT, MainCategory.BASIC_BENEFIT,
                "SKT 오프라인 혜택", Grade.SKT_GOLD, "SKT 골드 할인");
        Benefit sktVip = benefit(
                2L, partner, Carrier.SKT, MainCategory.VIP_COCK,
                "SKT VIP 혜택", Grade.SKT_VIP, "SKT VIP 추가 혜택");
        Benefit lguBasic = benefit(
                3L, partner, Carrier.LGU, MainCategory.BASIC_BENEFIT,
                "LGU 오프라인 혜택", Grade.VIP, "LGU VIP 할인");

        when(storeRepository.findByIdAndPartnerId(20L, 10L)).thenReturn(Optional.of(store));
        when(benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(10L, null))
                .thenReturn(List.of(lguBasic, sktVip, sktBasic));

        MapBenefitDetailResponse response = benefitService.getMapBenefitDetail(
                20L, 10L, null, Carrier.SKT, null);

        assertThat(response.getBenefitId()).isEqualTo(1L);
        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.SKT, Carrier.LGU);
        assertThat(response.getTierBenefits())
                .extracting(tier -> tier.getContext())
                .containsExactly("SKT 골드 할인", "SKT VIP 추가 혜택", "LGU VIP 할인");
    }


    @Test
    void getMapBenefitDetail_rejectsSeoulLandBenefitForFancyLandStoreWhenPartnerDoesNotMatch() {
        when(storeRepository.findByIdAndPartnerId(20L, 100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> benefitService.getMapBenefitDetail(
                20L, 100L, MainCategory.BASIC_BENEFIT, Carrier.SKT, null))
                .isInstanceOf(StorePartnerMismatchException.class)
                .hasMessage("지점과 제휴사가 일치하지 않습니다.");
    }

    private Benefit benefit(
            Long benefitId,
            Partner partner,
            Carrier carrier,
            MainCategory mainCategory,
            String benefitName,
            Grade grade,
            String context
    ) {
        Benefit benefit = Benefit.builder()
                .benefitId(benefitId)
                .partner(partner)
                .benefitName(benefitName)
                .mainCategory(mainCategory)
                .build();
        if (carrier != null) {
            BenefitCarrierPolicy policy = policy(benefitId + 1000, benefit, carrier, carrier.name() + " manual", carrier.name() + " url");
            policy.setTierBenefits(List.of(carrierTier(policy, grade, context)));
            benefit.setCarrierPolicies(List.of(policy));
        }
        return benefit;
    }

    private Benefit benefit(
            Long benefitId,
            Partner partner,
            Carrier carrier,
            String benefitName,
            Grade grade,
            String context
    ) {
        return benefit(benefitId, partner, carrier, MainCategory.BASIC_BENEFIT, benefitName, grade, context);
    }

    private BenefitCarrierPolicy policy(
            Long policyId,
            Benefit benefit,
            Carrier carrier,
            String manual,
            String url
    ) {
        return BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(policyId)
                .benefit(benefit)
                .carrier(carrier)
                .active(true)
                .usageType(UsageType.OFFLINE)
                .manual(manual)
                .url(url)
                .build();
    }

    private CarrierTierBenefit carrierTier(BenefitCarrierPolicy policy, Grade grade, String context) {
        return CarrierTierBenefit.builder()
                .benefitCarrierPolicy(policy)
                .grade(grade)
                .context(context)
                .isAll(false)
                .build();
    }
}
