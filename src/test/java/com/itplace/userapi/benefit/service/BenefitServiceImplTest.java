package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
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
import java.util.Arrays;
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

    @Mock
    private BenefitHybridSearchService benefitHybridSearchService;

    @InjectMocks
    private BenefitServiceImpl benefitService;

    @Test
    void getBenefitList_usesHybridSearchForKeywordAndPreservesRankOrder() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("IT 카페")
                .category("카페")
                .image("image")
                .build();
        Benefit benefit = benefit(20L, partner, Carrier.LGU, "아메리카노 할인", Grade.VIP, "VIP 커피 할인");
        BenefitCarrierPolicy policy = policy(120L, benefit, Carrier.LGU, "LGU 사용법", "https://lgu.example");
        CarrierTierBenefit tier = carrierTier(policy, Grade.VIP, "VIP 커피 할인");

        when(benefitHybridSearchService.search(eq("커피"), eq(MainCategory.BASIC_BENEFIT), eq("카페"),
                eq(UsageType.OFFLINE), eq(List.of(Carrier.LGU)), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(20L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(policy))).thenReturn(List.of(tier));
        when(favoriteRepository.countFavoritesByBenefitIds(List.of(20L))).thenReturn(java.util.Collections.singletonList(new Object[]{20L, 3L}));

        var result = benefitService.getBenefitList(
                MainCategory.BASIC_BENEFIT,
                "카페",
                UsageType.OFFLINE,
                null,
                "  커피  ",
                List.of(Carrier.LGU),
                null,
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getBenefitId()).isEqualTo(20L);
                    assertThat(item.getBenefitName()).isEqualTo("아메리카노 할인");
                    assertThat(item.getCarrier()).isEqualTo(Carrier.LGU);
                    assertThat(item.getFavoriteCount()).isEqualTo(3L);
                });
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(benefitRepository, never()).findFilteredBenefits(any(), any(), any(), any(), anyBoolean(), anyList(), any(), any());
    }

    @Test
    void getBenefitList_expandsHybridMatchToSamePartnerCarrierBenefits() {
        Partner partner = Partner.builder()
                .partnerId(30L)
                .partnerName("배스킨라빈스")
                .category("푸드")
                .image("image")
                .build();
        Benefit kt = benefit(510L, partner, Carrier.KT, "배스킨라빈스 KT 멤버십", Grade.KT_VIP, "아이스크림 케이크 할인");
        Benefit lgu = benefit(647L, partner, Carrier.LGU, "배스킨라빈스 LGU+ 멤버십", Grade.VIP, "쿼터 사이즈 할인");
        BenefitCarrierPolicy ktPolicy = kt.getCarrierPolicies().get(0);
        BenefitCarrierPolicy lguPolicy = lgu.getCarrierPolicies().get(0);

        when(benefitHybridSearchService.search(eq("아이스크림"), eq(null), eq(null),
                eq(null), eq(List.of()), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(510L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(510L))).thenReturn(List.of(kt));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(30L)), eq(null), eq(null), eq(null), eq(Arrays.stream(Carrier.values()).toList())))
                .thenReturn(List.of(kt, lgu));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(kt, lgu))).thenReturn(List.of(ktPolicy, lguPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(ktPolicy, lguPolicy)))
                .thenReturn(List.of(
                        carrierTier(ktPolicy, Grade.KT_VIP, "아이스크림 케이크 할인"),
                        carrierTier(lguPolicy, Grade.VIP, "쿼터 사이즈 할인")
                ));
        when(favoriteRepository.countFavoritesByBenefitIds(List.of(510L, 647L))).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                null,
                null,
                null,
                null,
                "아이스크림",
                List.of(),
                null,
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .extracting(BenefitListResponse::getBenefitId)
                .containsExactly(510L, 647L);
        assertThat(result.getContent())
                .extracting(BenefitListResponse::getCarrier)
                .containsExactly(Carrier.KT, Carrier.LGU);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void getBenefitList_paginatesAfterHybridPartnerExpansion() {
        Partner partner = Partner.builder()
                .partnerId(30L)
                .partnerName("배스킨라빈스")
                .category("푸드")
                .image("image")
                .build();
        Benefit kt = benefit(510L, partner, Carrier.KT, "배스킨라빈스 KT 멤버십", Grade.KT_VIP, "아이스크림 케이크 할인");
        Benefit lgu = benefit(647L, partner, Carrier.LGU, "배스킨라빈스 LGU+ 멤버십", Grade.VIP, "쿼터 사이즈 할인");
        BenefitCarrierPolicy ktPolicy = kt.getCarrierPolicies().get(0);

        when(benefitHybridSearchService.search(eq("아이스크림"), eq(null), eq(null),
                eq(null), eq(List.of()), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(510L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(510L))).thenReturn(List.of(kt));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(30L)), eq(null), eq(null), eq(null), eq(Arrays.stream(Carrier.values()).toList())))
                .thenReturn(List.of(kt, lgu));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(ktPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(ktPolicy))).thenReturn(List.of());
        when(favoriteRepository.countFavoritesByBenefitIds(anyList())).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                null,
                null,
                null,
                null,
                "아이스크림",
                List.of(),
                null,
                PageRequest.of(0, 1)
        );

        assertThat(result.getContent())
                .extracting(BenefitListResponse::getBenefitId)
                .containsExactly(510L);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.isHasNext()).isTrue();
    }

    @Test
    void getBenefitList_fallsBackToDatabaseSearchWhenHybridSearchFails() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("IT 카페")
                .category("카페")
                .image("image")
                .build();
        Benefit benefit = benefit(20L, partner, Carrier.LGU, "아메리카노 할인", Grade.VIP, "VIP 커피 할인");
        BenefitCarrierPolicy policy = policy(120L, benefit, Carrier.LGU, "LGU 사용법", "https://lgu.example");

        when(benefitHybridSearchService.search(eq("커피"), eq(MainCategory.BASIC_BENEFIT), eq("카페"),
                eq(UsageType.OFFLINE), eq(List.of(Carrier.LGU)), any(PageRequest.class)))
                .thenThrow(new IllegalStateException("es unavailable"));
        when(benefitRepository.findFilteredBenefits(eq(MainCategory.BASIC_BENEFIT.getLabel()), eq("카페"),
                eq(UsageType.OFFLINE.name()), eq("커피"), eq(true), eq(List.of("LGU")), eq("RELEVANCE"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(benefit), PageRequest.of(0, 12), 1));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(10L)), eq(MainCategory.BASIC_BENEFIT), eq("카페"), eq(UsageType.OFFLINE), eq(List.of(Carrier.LGU))))
                .thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(policy))).thenReturn(List.of());
        when(favoriteRepository.countFavoritesByBenefitIds(List.of(20L))).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                MainCategory.BASIC_BENEFIT,
                "카페",
                UsageType.OFFLINE,
                null,
                "  커피  ",
                List.of(Carrier.LGU),
                null,
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> assertThat(item.getBenefitId()).isEqualTo(20L));
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(benefitRepository).findFilteredBenefits(eq(MainCategory.BASIC_BENEFIT.getLabel()), eq("카페"),
                eq(UsageType.OFFLINE.name()), eq("커피"), eq(true), eq(List.of("LGU")), eq("RELEVANCE"), any(PageRequest.class));
    }

    @Test
    void getBenefitList_usesLexicalSearchWhenKeywordSortIsPopularity() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("IT 카페")
                .category("카페")
                .image("image")
                .build();
        Benefit benefit = benefit(20L, partner, Carrier.LGU, "아메리카노 할인", Grade.VIP, "VIP 커피 할인");
        BenefitCarrierPolicy policy = policy(120L, benefit, Carrier.LGU, "LGU 사용법", "https://lgu.example");

        when(benefitHybridSearchService.searchLexical(eq("커피"), eq(MainCategory.BASIC_BENEFIT), eq("카페"),
                eq(UsageType.OFFLINE), eq(List.of(Carrier.LGU)), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(20L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(10L)), eq(MainCategory.BASIC_BENEFIT), eq("카페"), eq(UsageType.OFFLINE), eq(List.of(Carrier.LGU))))
                .thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(policy))).thenReturn(List.of());
        when(favoriteRepository.countFavoritesByBenefitIds(List.of(20L))).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                MainCategory.BASIC_BENEFIT,
                "카페",
                UsageType.OFFLINE,
                "POPULARITY",
                "  커피  ",
                List.of(Carrier.LGU),
                null,
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> assertThat(item.getBenefitId()).isEqualTo(20L));
        verify(benefitHybridSearchService, never()).search(any(), any(), any(), any(), anyList(), any(PageRequest.class));
        verify(benefitRepository, never()).findFilteredBenefits(any(), any(), any(), any(), anyBoolean(), anyList(), any(), any());
    }

    @Test
    void getBenefitList_expandsLexicalSortedSearchToSamePartnerCarrierBenefits() {
        Partner partner = Partner.builder()
                .partnerId(30L)
                .partnerName("배스킨라빈스")
                .category("푸드")
                .image("image")
                .build();
        Benefit kt = benefit(510L, partner, Carrier.KT, "배스킨라빈스 KT 멤버십", Grade.KT_VIP, "아이스크림 케이크 할인");
        Benefit lgu = benefit(647L, partner, Carrier.LGU, "배스킨라빈스 LGU+ 멤버십", Grade.VIP, "쿼터 사이즈 할인");
        BenefitCarrierPolicy ktPolicy = kt.getCarrierPolicies().get(0);
        BenefitCarrierPolicy lguPolicy = lgu.getCarrierPolicies().get(0);

        when(benefitHybridSearchService.searchLexical(eq("아이스크림"), eq(null), eq(null),
                eq(null), eq(List.of()), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(510L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(510L))).thenReturn(List.of(kt));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(30L)), eq(null), eq(null), eq(null), eq(Arrays.stream(Carrier.values()).toList())))
                .thenReturn(List.of(kt, lgu));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(kt, lgu))).thenReturn(List.of(ktPolicy, lguPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(ktPolicy, lguPolicy)))
                .thenReturn(List.of());
        when(favoriteRepository.countFavoritesByBenefitIds(anyList())).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                null,
                null,
                null,
                "POPULARITY",
                "아이스크림",
                List.of(),
                null,
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .extracting(BenefitListResponse::getBenefitId)
                .containsExactly(510L, 647L);
        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(benefitHybridSearchService, never()).search(any(), any(), any(), any(), anyList(), any(PageRequest.class));
        verify(benefitRepository, never()).findFilteredBenefits(any(), any(), any(), any(), anyBoolean(), anyList(), any(), any());
    }

    @Test
    void getBenefitList_paginatesAfterLexicalSortedPartnerExpansion() {
        Partner partner = Partner.builder()
                .partnerId(30L)
                .partnerName("배스킨라빈스")
                .category("푸드")
                .image("image")
                .build();
        Benefit kt = benefit(510L, partner, Carrier.KT, "배스킨라빈스 KT 멤버십", Grade.KT_VIP, "아이스크림 케이크 할인");
        Benefit lgu = benefit(647L, partner, Carrier.LGU, "배스킨라빈스 LGU+ 멤버십", Grade.VIP, "쿼터 사이즈 할인");
        BenefitCarrierPolicy ktPolicy = kt.getCarrierPolicies().get(0);

        when(benefitHybridSearchService.searchLexical(eq("아이스크림"), eq(null), eq(null),
                eq(null), eq(List.of()), any(PageRequest.class)))
                .thenReturn(new BenefitHybridSearchResult(List.of(510L), 1, 0, 1, false));
        when(benefitRepository.findAllByIdWithPartner(List.of(510L))).thenReturn(List.of(kt));
        when(benefitRepository.findActiveBenefitsByPartnerIdsWithPartner(
                eq(List.of(30L)), eq(null), eq(null), eq(null), eq(Arrays.stream(Carrier.values()).toList())))
                .thenReturn(List.of(kt, lgu));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(anyList())).thenReturn(List.of(ktPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(ktPolicy))).thenReturn(List.of());
        when(favoriteRepository.countFavoritesByBenefitIds(anyList())).thenReturn(List.of());

        var result = benefitService.getBenefitList(
                null,
                null,
                null,
                "POPULARITY",
                "아이스크림",
                List.of(),
                null,
                PageRequest.of(0, 1)
        );

        assertThat(result.getContent())
                .extracting(BenefitListResponse::getBenefitId)
                .containsExactly(510L);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.isHasNext()).isTrue();
        verify(benefitHybridSearchService, never()).search(any(), any(), any(), any(), anyList(), any(PageRequest.class));
        verify(benefitRepository, never()).findFilteredBenefits(any(), any(), any(), any(), anyBoolean(), anyList(), any(), any());
    }

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
    void getMapBenefitDetail_splitsOnlineAndOfflineContextsWithoutChangingStoredContext() {
        Partner partner = Partner.builder()
                .partnerId(10L)
                .partnerName("미스터피자")
                .image("image")
                .build();
        Store store = Store.builder()
                .storeId(20L)
                .partner(partner)
                .build();
        Benefit skt = benefit(
                1L,
                partner,
                Carrier.SKT,
                "미스터피자 할인형",
                Grade.SKT_VIP,
                "온라인: 25% 할인 / 오프라인: 25% 할인"
        );

        when(storeRepository.findByIdAndPartnerId(20L, 10L)).thenReturn(Optional.of(store));
        when(benefitRepository.findMapBenefitsWithPartnerAndTierBenefits(10L, MainCategory.BASIC_BENEFIT))
                .thenReturn(List.of(skt));

        MapBenefitDetailResponse response = benefitService.getMapBenefitDetail(
                20L, 10L, MainCategory.BASIC_BENEFIT, Carrier.SKT, null);

        assertThat(response.getTierBenefits()).singleElement()
                .satisfies(tier -> {
                    assertThat(tier.getContext()).isEqualTo("온라인: 25% 할인 / 오프라인: 25% 할인");
                    assertThat(tier.getOnlineContext()).isEqualTo("25% 할인");
                    assertThat(tier.getOfflineContext()).isEqualTo("25% 할인");
                });
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
    void getMapBenefitDetail_rejectsDaracBenefitForCareCenterNameCollision() {
        Partner darac = Partner.builder()
                .partnerId(8L)
                .partnerName("다락")
                .image("dalock.png")
                .build();
        Store careCenter = Store.builder()
                .storeId(30619L)
                .partner(darac)
                .storeName("상계다락 아이휴센터 우리동네키움센터 노원16호점")
                .business("사회,공공기관 > 단체,협회 > 사회복지시설 > 아동복지시설")
                .build();

        when(storeRepository.findByIdAndPartnerId(30619L, 8L)).thenReturn(Optional.of(careCenter));

        assertThatThrownBy(() -> benefitService.getMapBenefitDetail(
                30619L, 8L, MainCategory.BASIC_BENEFIT, Carrier.KT, null))
                .isInstanceOf(StorePartnerMismatchException.class)
                .hasMessage("지점과 제휴사가 일치하지 않습니다.");
        verify(benefitRepository, never())
                .findMapBenefitsWithPartnerAndTierBenefits(8L, MainCategory.BASIC_BENEFIT);
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
