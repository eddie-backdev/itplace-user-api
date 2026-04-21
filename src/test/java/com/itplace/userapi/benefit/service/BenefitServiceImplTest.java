package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.entity.Partner;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitServiceImplTest {

    @Mock
    private TierBenefitRepository tierBenefitRepository;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private StoreRepository storeRepository;

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
                .description("설명")
                .manual(null)
                .url(null)
                .partner(partner)
                .benefitPolicy(BenefitPolicy.builder().name("월 1회").build())
                .build();

        when(benefitRepository.findBenefitWithPartnerById(1L)).thenReturn(Optional.of(benefit));

        BenefitDetailResponse response = benefitService.getBenefitDetail(1L);

        assertThat(response.getManual()).isNull();
        assertThat(response.getUrl()).isNull();
        assertThat(response.getPartnerName()).isEqualTo("파트너");
    }
}
