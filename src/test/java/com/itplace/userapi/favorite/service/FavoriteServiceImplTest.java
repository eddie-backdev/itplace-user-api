package com.itplace.userapi.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.dto.response.FavoriteDetailResponse;
import com.itplace.userapi.favorite.dto.response.FavoriteResponse;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Mock
    private LogService logService;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    @Test
    void removeFavoritesWritesFavoriteRemoveEventBeforeCacheInvalidationChecks() {
        User user = User.builder().id(7L).role(Role.USER).build();
        Benefit benefit = Benefit.builder()
                .benefitId(100L)
                .partner(Partner.builder().partnerId(200L).partnerName("파트너").build())
                .build();

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllByIdWithPartner(List.of(100L))).thenReturn(List.of(benefit));

        favoriteService.removeFavorites(7L, List.of(100L));

        verify(logService).saveResponseLog(
                7L,
                "favorite_remove",
                100L,
                200L,
                "/api/v1/favorites",
                "benefitId=100"
        );
        verify(favoriteRepository).deleteByUserAndBenefitIn(user, List.of(benefit));
    }

    @Test
    void getFavoritesUsesPageQueryThatFetchesBenefitAndPartner() {
        User user = User.builder().id(7L).role(Role.USER).build();
        Partner partner = Partner.builder().partnerId(200L).partnerName("파트너").image("logo.png").build();
        Benefit benefit = Benefit.builder()
                .benefitId(100L)
                .benefitName("공통 혜택")
                .partner(partner)
                .build();
        Favorite favorite = Favorite.builder().user(user).benefit(benefit).build();
        PageRequest pageable = PageRequest.of(0, 6);

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(favoriteRepository.findPageByUserWithBenefitAndPartner(user, pageable))
                .thenReturn(new PageImpl<>(List.of(favorite), pageable, 1));

        List<FavoriteResponse> responses = favoriteService.getFavorites(7L, null, pageable).getContent();

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getBenefitId()).isEqualTo(100L);
            assertThat(response.getPartnerName()).isEqualTo("파트너");
            assertThat(response.getPartnerImage()).isEqualTo("logo.png");
        });
        verify(favoriteRepository).findPageByUserWithBenefitAndPartner(user, pageable);
    }
    @Test
    void getBenefitDetailReturnsTiersForEveryCarrierPolicy() {
        Partner partner = Partner.builder().partnerId(200L).partnerName("파트너").image("logo.png").build();
        Benefit benefit = Benefit.builder()
                .benefitId(100L)
                .benefitName("공통 혜택")
                .partner(partner)
                .build();
        BenefitCarrierPolicy sktPolicy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .carrier(Carrier.SKT)
                .description("SKT 설명")
                .build();
        BenefitCarrierPolicy ktPolicy = BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .carrier(Carrier.KT)
                .description("KT 설명")
                .build();
        CarrierTierBenefit sktTier = CarrierTierBenefit.builder()
                .benefitCarrierPolicy(sktPolicy)
                .grade(Grade.SKT_VIP)
                .isAll(false)
                .context("SKT VIP 혜택")
                .discountValue(10)
                .build();
        CarrierTierBenefit ktTier = CarrierTierBenefit.builder()
                .benefitCarrierPolicy(ktPolicy)
                .grade(Grade.KT_VIP)
                .isAll(false)
                .context("KT VIP 혜택")
                .discountValue(20)
                .build();

        when(benefitRepository.findDetailById(100L)).thenReturn(Optional.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)))
                .thenReturn(List.of(sktPolicy, ktPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(sktPolicy, ktPolicy)))
                .thenReturn(List.of(sktTier, ktTier));

        FavoriteDetailResponse response = favoriteService.getBenefitDetail(100L);

        assertThat(response.getTiers())
                .extracting("carrier", "grade")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Carrier.SKT, Grade.SKT_VIP),
                        org.assertj.core.groups.Tuple.tuple(Carrier.KT, Grade.KT_VIP)
                );
    }

}
