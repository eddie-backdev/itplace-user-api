package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
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
class PartnerBenefitBrowseServiceTest {

    @Mock
    private PartnerRepository partnerRepository;
    @Mock
    private BenefitRepository benefitRepository;
    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;
    @Mock
    private FavoriteRepository favoriteRepository;

    @InjectMocks
    private PartnerBenefitBrowseService service;

    @Test
    void getPartners_returnsOnePartnerWithEveryAvailableCarrier() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy skt = policy(101L, benefit, Carrier.SKT, "SKT 혜택");
        BenefitCarrierPolicy lgu = policy(102L, benefit, Carrier.LGU, "LGU 혜택");
        PageRequest pageable = PageRequest.of(0, 9);

        when(partnerRepository.findBenefitPartners(
                eq(MainCategory.BASIC_BENEFIT.getLabel()), eq("생활/편의"), eq(null), eq("GS25"),
                eq(true), eq(List.of("SKT")), eq("POPULARITY"), eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(partner), pageable, 1));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(skt, lgu));

        var result = service.getPartners(
                MainCategory.BASIC_BENEFIT,
                "생활/편의",
                null,
                null,
                " GS25 ",
                List.of(Carrier.SKT),
                pageable
        );

        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.getPartnerId()).isEqualTo(10L);
            assertThat(item.getPartnerName()).isEqualTo("GS25");
            assertThat(item.getCarriers()).containsExactly(Carrier.SKT, Carrier.LGU);
        });
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getPartnerDetail_groupsBenefitsByCarrierAndIncludesTierDetails() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy skt = policy(101L, benefit, Carrier.SKT, "SKT GS25 할인");
        BenefitCarrierPolicy lgu = policy(102L, benefit, Carrier.LGU, "LGU GS25 할인");
        CarrierTierBenefit sktTier = tier(skt, Grade.SKT_VIP, "1천원당 100원 할인");
        CarrierTierBenefit lguTier = tier(lgu, Grade.VIP, "1천원당 50원 할인");

        when(partnerRepository.findById(10L)).thenReturn(Optional.of(partner));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(lgu, skt));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(lgu, skt)))
                .thenReturn(List.of(lguTier, sktTier));
        when(favoriteRepository.findFavoriteBenefitIdsByUser(7L, List.of(20L))).thenReturn(List.of(20L));
        when(favoriteRepository.countFavoritesByBenefitIds(List.of(20L)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{20L, 4L}));

        var result = service.getPartnerDetail(10L, MainCategory.BASIC_BENEFIT, 7L);

        assertThat(result.getPartnerName()).isEqualTo("GS25");
        assertThat(result.getCarrierGroups()).extracting(group -> group.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.LGU);
        assertThat(result.getCarrierGroups().get(0).getBenefits()).singleElement().satisfies(item -> {
            assertThat(item.getBenefitId()).isEqualTo(20L);
            assertThat(item.getBenefitName()).isEqualTo("SKT GS25 할인");
            assertThat(item.getTierBenefits()).singleElement().satisfies(tier -> {
                assertThat(tier.getGrade()).isEqualTo(Grade.SKT_VIP);
                assertThat(tier.getContext()).isEqualTo("1천원당 100원 할인");
            });
            assertThat(item.getIsFavorite()).isTrue();
            assertThat(item.getFavoriteCount()).isEqualTo(4L);
        });
    }

    private Partner partner() {
        return Partner.builder()
                .partnerId(10L)
                .partnerName("GS25")
                .category("생활/편의")
                .image("gs25.png")
                .build();
    }

    private Benefit benefit(Partner partner) {
        return Benefit.builder()
                .benefitId(20L)
                .partner(partner)
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .benefitName("GS25 멤버십")
                .active(true)
                .build();
    }

    private BenefitCarrierPolicy policy(Long id, Benefit benefit, Carrier carrier, String name) {
        return BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(id)
                .benefit(benefit)
                .carrier(carrier)
                .carrierBenefitName(name)
                .active(true)
                .usageType(UsageType.OFFLINE)
                .description("설명")
                .manual("이용 방법")
                .url("https://example.com")
                .build();
    }

    private CarrierTierBenefit tier(BenefitCarrierPolicy policy, Grade grade, String context) {
        return CarrierTierBenefit.builder()
                .carrierTierBenefitId(policy.getBenefitCarrierPolicyId() + 1000)
                .benefitCarrierPolicy(policy)
                .grade(grade)
                .context(context)
                .isAll(false)
                .build();
    }
}
