package com.itplace.userapi.favorite.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
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
        when(benefitRepository.findAllById(List.of(100L))).thenReturn(List.of(benefit));

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
}
