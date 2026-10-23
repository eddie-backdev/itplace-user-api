package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.map.repository.StoreRepository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock private ReferenceDataService referenceDataService;
    @Mock private StoreRepository storeRepository;
    private BenefitOfferMatcher benefitOfferMatcher;

    @InjectMocks
    private PartnerBenefitBrowseService service;

    @BeforeEach void stubReferenceCatalog() {
        benefitOfferMatcher = org.mockito.Mockito.spy(new BenefitOfferMatcher(referenceDataService));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "benefitOfferMatcher", benefitOfferMatcher);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "previewSummaryBuilder",
                new BenefitPreviewSummaryBuilder(benefitOfferMatcher));
        lenient().when(referenceDataService.isActiveProgram(any())).thenReturn(true);
        lenient().when(referenceDataService.isActiveCategory(any())).thenReturn(true);
        lenient().when(referenceDataService.isBenefitGrade(any(Carrier.class), any())).thenAnswer(i -> {
            Carrier carrier = i.getArgument(0); Grade grade = i.getArgument(1);
            return carrier == Carrier.LGU ? List.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.DIAMOND, Grade.VIP콕).contains(grade)
                    : carrier == Carrier.SKT ? List.of(Grade.SKT_LITE, Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP).contains(grade)
                    : carrier == Carrier.KT && List.of(Grade.KT_GENERAL, Grade.KT_WHITE, Grade.KT_SILVER, Grade.KT_GOLD, Grade.KT_VIP, Grade.KT_VVIP).contains(grade);
        });
        lenient().when(referenceDataService.gradesForProfile(any(Carrier.class), any())).thenAnswer(i -> {
            Carrier carrier = i.getArgument(0); Grade grade = i.getArgument(1);
            return carrier == Carrier.LGU && (grade.equals(Grade.VIP) || grade.equals(Grade.VVIP))
                    ? List.<Grade>of(grade, Grade.VIP콕) : java.util.Collections.singletonList(grade);
        });
        lenient().when(referenceDataService.allGradeMembers(Carrier.SKT)).thenReturn(List.of(Grade.SKT_LITE, Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP));
        lenient().when(referenceDataService.allGradeMembers(Carrier.KT)).thenReturn(List.of(Grade.KT_GENERAL, Grade.KT_WHITE, Grade.KT_SILVER, Grade.KT_GOLD, Grade.KT_VIP, Grade.KT_VVIP));
        lenient().when(referenceDataService.allGradeMembers(Carrier.LGU)).thenReturn(List.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.DIAMOND));
    }

    @Test
    void getPartnersExpandsKnownSubtypeIntentToExactPartnerNames() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq(null), eq("편의점"),
                eq(List.of("gs25", "cu", "세븐일레븐", "이마트24")), eq(false),
                eq(List.of("SKT", "KT", "LGU")), eq(false), eq(List.of("")),
                eq("POPULARITY"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var result = service.getPartners(null, null, null, null, " 편의점 ", List.of(), null, pageable);

        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void naturalShortSearchPhrasesKeepTheirWordsAndExpandKnownPartnerBrands() {
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("편의점 할인")).containsExactly("gs25", "cu", "세븐일레븐", "이마트24");
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("영화 혜택")).containsExactly("cgv", "롯데시네마", "메가박스", "씨네q");
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("스타벅스 영화")).containsExactly("");
        assertThat(PartnerBrowseSearchIntents.normalizeKeyword("GS25 혜택")).isEqualTo("GS25");
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("GS25 혜택")).containsExactly("");
        assertThat(PartnerBrowseSearchIntents.normalizeKeyword("영화 말고 카페")).isEqualTo("영화 말고 카페");
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("영화 말고 카페")).containsExactly("");
        assertThat(PartnerBrowseSearchIntents.normalizeKeyword("편의점 택배")).isEqualTo("편의점 택배");
        assertThat(PartnerBrowseSearchIntents.exactPartnerNames("편의점 택배")).containsExactly("");
    }

    @Test
    void getPartnersPassesNormalizedPhraseAndOnlyExactConceptBrandExpansion() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq(null), eq("편의점"),
                eq(List.of("gs25", "cu", "세븐일레븐", "이마트24")), eq(false),
                eq(List.of("SKT", "KT", "LGU")), eq(false), eq(List.of("")),
                eq("POPULARITY"), eq(pageable))).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.getPartners(null, null, null, null, "편의점 할인 좀 알려줘", List.of(), null, pageable);

        verify(partnerRepository).findBenefitPartners(eq(null), eq(null), eq(null), eq("편의점"),
                eq(List.of("gs25", "cu", "세븐일레븐", "이마트24")), eq(false),
                eq(List.of("SKT", "KT", "LGU")), eq(false), eq(List.of("")),
                eq("POPULARITY"), eq(pageable));
    }

    @Test
    void getPartners_returnsOnePartnerWithEveryAvailableCarrier() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy skt = policy(101L, benefit, Carrier.SKT, "SKT 혜택");
        BenefitCarrierPolicy lgu = policy(102L, benefit, Carrier.LGU, "LGU 혜택");
        PageRequest pageable = PageRequest.of(0, 9);

        when(partnerRepository.findBenefitPartners(
                eq(MainCategory.BASIC_BENEFIT.getCode()), eq("생활/편의"), eq(null), eq("GS25"), eq(List.of("")),
                eq(true), eq(List.of("SKT")), eq(false), eq(List.of("")), eq("POPULARITY"), eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(partner), pageable, 1));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(skt, lgu));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any()))
                .thenReturn(List.of(tier(skt, Grade.SKT_VIP, "SKT 할인"), tier(lgu, Grade.VIP, "LGU 할인")));

        var result = service.getPartners(
                MainCategory.BASIC_BENEFIT,
                "생활/편의",
                null,
                null,
                " GS25 ",
                List.of(Carrier.SKT),
                null,
                pageable
        );

        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.getPartnerId()).isEqualTo(10L);
            assertThat(item.getPartnerName()).isEqualTo("GS25");
            assertThat(item.getCarriers()).containsExactly(Carrier.SKT, Carrier.LGU);
            assertThat(item.getPreviews()).singleElement().satisfies(preview -> {
                assertThat(preview.carrier()).isEqualTo(Carrier.SKT);
                assertThat(preview.benefitId()).isEqualTo(20L);
                assertThat(preview.benefitName()).isEqualTo("SKT 혜택");
                assertThat(preview.benefitLimit()).isNull();
                assertThat(preview.tierBenefits()).singleElement().satisfies(tier ->
                        assertThat(tier.context()).isEqualTo("SKT 할인"));
            });
        });
        assertThat(result.getTotalElements()).isEqualTo(1);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    void getPartners_batchesPreviewsAndSelectsFirstValidPolicyPerCarrier() {
        Partner firstPartner = partner();
        Partner secondPartner = Partner.builder().partnerId(11L).partnerName("CU").build();
        Benefit first = benefit(firstPartner);
        Benefit blank = benefit(firstPartner);
        blank.setBenefitId(19L);
        Benefit later = benefit(firstPartner);
        later.setBenefitId(22L);
        Benefit second = benefit(secondPartner);
        second.setBenefitId(21L);
        Benefit vipCategory = benefit(firstPartner);
        vipCategory.setBenefitId(17L);
        vipCategory.setMainCategory(MainCategory.VIP_COCK);
        Benefit inactive = benefit(firstPartner);
        inactive.setBenefitId(18L);
        inactive.setActive(false);

        BenefitCarrierPolicy blankSkt = policy(99L, blank, Carrier.SKT, "내용 없음");
        BenefitCarrierPolicy selectedSkt = policy(101L, first, Carrier.SKT, "첫 SKT 혜택");
        selectedSkt.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("movie-online", "영화 할인", "온라인 예매", UsageType.ONLINE,
                                List.of("SKT_VIP"), false, List.of("영화 예매"), List.of(), List.of(), List.of(), List.of(), List.of()),
                        new BenefitOffer("snack-offline", "매점 할인", "현장 매점", UsageType.OFFLINE,
                                List.of("SKT_VIP"), false, List.of("매점 상품"), List.of(), List.of(), List.of(), List.of(), List.of()),
                        new BenefitOffer("all-channels", "전 채널 이용", "온오프라인 모두", UsageType.BOTH,
                                List.of("SKT_VIP"), false, List.of("멤버십 할인"), List.of(), List.of(), List.of(), List.of(), List.of())),
                com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null, List.of(),
                List.of(new com.itplace.userapi.benefit.dto.NormalizationIssue(
                        "UNASSIGNED_SHARED_RULE", "internal", "월 한도는 제휴사 전체 공유", "html/body/rule"))));
        selectedSkt.setManual("전체 공식 안내 원문");
        selectedSkt.setSourceUrl("https://carrier.example/benefit");
        selectedSkt.setSourceCapturedAt(Instant.parse("2026-10-19T07:30:00Z"));
        selectedSkt.setBenefitPolicy(BenefitPolicy.builder()
                .code(BenefitPolicyCode.UNLIMITED).name("제한없음").build());
        BenefitCarrierPolicy sameBenefitSkt = policy(103L, first, Carrier.SKT, "같은 혜택의 다른 정책");
        BenefitCarrierPolicy laterSkt = policy(105L, later, Carrier.SKT, "나중 SKT 혜택");
        BenefitCarrierPolicy lguBoth = policy(102L, first, Carrier.LGU, "LGU 온오프라인");
        lguBoth.setUsageType(UsageType.BOTH);
        BenefitCarrierPolicy ktOnline = policy(104L, first, Carrier.KT, "KT 온라인");
        ktOnline.setUsageType(UsageType.ONLINE);
        BenefitCarrierPolicy inactivePolicy = policy(98L, blank, Carrier.LGU, "종료된 정책");
        inactivePolicy.setActive(false);
        BenefitCarrierPolicy secondSkt = policy(106L, second, Carrier.SKT, "CU SKT 혜택");
        PageRequest pageable = PageRequest.of(0, 9);

        when(partnerRepository.findBenefitPartners(
                eq(MainCategory.BASIC_BENEFIT.getCode()), eq(null), eq("OFFLINE"), eq(null), eq(List.of("")),
                eq(false), eq(List.of("SKT", "KT", "LGU")), eq(false), eq(List.of("")),
                eq("POPULARITY"), eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(firstPartner, secondPartner), pageable, 2));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L, 11L)))
                .thenReturn(List.of(later, vipCategory, blank, first, inactive, second));
        List<Benefit> activeBenefits = List.of(later, blank, first, second);
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(activeBenefits)).thenReturn(List.of(
                laterSkt, sameBenefitSkt, blankSkt, inactivePolicy, ktOnline, lguBoth, secondSkt, selectedSkt));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(
                tier(blankSkt, Grade.SKT_VIP, "  "), tier(selectedSkt, Grade.SKT_VIP, "1천원당 100원 할인"),
                tier(sameBenefitSkt, Grade.SKT_VIP, "선택하지 않는 조건"),
                tier(laterSkt, Grade.SKT_VIP, "나중 조건"), tier(lguBoth, Grade.VIP, "LGU 할인"),
                tier(ktOnline, Grade.KT_VIP, "온라인 전용"), tier(secondSkt, Grade.SKT_GOLD, "CU 할인")));

        var result = service.getPartners(MainCategory.BASIC_BENEFIT, null, UsageType.OFFLINE,
                null, null, List.of(), null, pageable);

        assertThat(result.getContent().get(0).getPreviews()).satisfies(previews -> {
            assertThat(previews).extracting(preview -> preview.carrier())
                    .containsExactly(Carrier.SKT, Carrier.LGU);
            assertThat(previews.get(0).benefitId()).isEqualTo(20L);
            assertThat(previews.get(0).benefitName()).isEqualTo("첫 SKT 혜택");
            assertThat(previews.get(0).benefitLimit()).isEqualTo("제한없음");
            assertThat(previews.get(0).usageType()).isEqualTo(UsageType.BOTH);
            assertThat(previews.get(0).offers()).isEmpty();
            assertThat(previews.get(0).summary()).isNotNull();
            assertThat(previews.get(0).summary().offerKey()).isEqualTo("snack-offline");
            assertThat(previews.get(0).normalizationIssues()).isEmpty();
            assertThat(previews.get(0).sourceUrl()).isEqualTo("https://carrier.example/benefit");
            assertThat(previews.get(0).sourceCapturedAt()).isEqualTo(Instant.parse("2026-10-19T07:30:00Z"));
            assertThat(previews.get(1).usageType()).isEqualTo(UsageType.BOTH);
        });
        assertThat(result.getContent().get(1).getPreviews()).singleElement()
                .satisfies(preview -> assertThat(preview.benefitId()).isEqualTo(21L));
        verify(partnerRepository).findBenefitPartners(any(), any(), any(), any(), any(), eq(false), any(),
                eq(false), any(), any(), eq(pageable));
        verify(benefitRepository).findAllByPartnerIdsWithPartner(List.of(10L, 11L));
        verify(benefitCarrierPolicyRepository).findAllByBenefitIn(activeBenefits);
        verify(carrierTierBenefitRepository).findAllByBenefitCarrierPolicyIn(any());
        verifyNoMoreInteractions(partnerRepository, benefitRepository,
                benefitCarrierPolicyRepository, carrierTierBenefitRepository);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    void getPartnerDetail_groupsBenefitsByCarrierAndIncludesTierDetails() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy skt = policy(101L, benefit, Carrier.SKT, "GS25 할인형");
        skt.setMembershipOptionType(com.itplace.userapi.benefit.entity.enums.MembershipOptionType.DISCOUNT);
        skt.setSourceCapturedAt(Instant.parse("2026-10-19T07:30:00Z"));
        skt.setConditions(new BenefitConditions(List.of("아이스컵 상품"), List.of("전국 매장"),
                List.of("1일 1회, 월 2회"), List.of("직원에게 멤버십 바코드 제시"), List.of("일부 특수매장 제외")));
        BenefitCarrierPolicy lgu = policy(102L, benefit, Carrier.LGU, "GS25 LGU+ 멤버십");
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

        var result = service.getPartnerDetail(10L, MainCategory.BASIC_BENEFIT, 7L, null, null);

        assertThat(result.getPartnerName()).isEqualTo("GS25");
        assertThat(result.getCarrierGroups()).extracting(group -> group.getCarrier())
                .containsExactly(Carrier.SKT, Carrier.LGU);
        assertThat(result.getCarrierGroups().get(0).getBenefits()).singleElement().satisfies(item -> {
            assertThat(item.getBenefitId()).isEqualTo(20L);
            assertThat(item.getBenefitName()).isEqualTo("GS25 멤버십 혜택 · 할인형");
            assertThat(item.getDisplayName()).isEqualTo("GS25 멤버십 혜택");
            assertThat(item.getSourceBenefitName()).isEqualTo("GS25 할인형");
            assertThat(item.getUrl()).isEqualTo("https://example.com");
            assertThat(item.getSourceUrl()).isEqualTo("https://carrier.example/detail");
            assertThat(item.getSourceCapturedAt()).isEqualTo(Instant.parse("2026-10-19T07:30:00Z"));
            assertThat(item.getConditions()).isEqualTo(new BenefitConditions(
                    List.of("아이스컵 상품"), List.of("전국 매장"), List.of("1일 1회, 월 2회"),
                    List.of("직원에게 멤버십 바코드 제시"), List.of("일부 특수매장 제외")));
            assertThat(item.getBenefitLimit()).isEqualTo("1일 1회, 월 2회");
            assertThat(item.getTierBenefits()).singleElement().satisfies(tier -> {
                assertThat(tier.getGrade()).isEqualTo(Grade.SKT_VIP);
                assertThat(tier.getContext()).isEqualTo("1천원당 100원 할인");
            });
            assertThat(item.getIsFavorite()).isTrue();
            assertThat(item.getFavoriteCount()).isEqualTo(4L);
        });
    }

    @Test
    void unfilteredDetailIncludesVipOnlyBenefitWhileExplicitBasicDoesNotFallback() {
        Partner partner = partner();
        Benefit vipBenefit = benefit(partner);
        vipBenefit.setMainCategory(MainCategory.VIP_COCK);
        BenefitCarrierPolicy policy = policy(101L, vipBenefit, Carrier.LGU, "VIP콕 제휴 혜택");
        when(partnerRepository.findById(10L)).thenReturn(Optional.of(partner));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(vipBenefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(vipBenefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(policy))).thenReturn(List.of());

        var result = service.getPartnerDetail(10L, null, null, null, null);

        assertThat(result.getCarrierGroups()).singleElement().satisfies(group ->
                assertThat(group.getBenefits()).singleElement()
                        .satisfies(item -> assertThat(item.getBenefitId()).isEqualTo(20L)));
        assertThatThrownBy(() -> service.getPartnerDetail(
                10L, MainCategory.BASIC_BENEFIT, null, null, null))
                .isInstanceOf(com.itplace.userapi.benefit.exception.BenefitNotFoundException.class);
    }

    @Test
    void membershipFilterRequiresOneMatchingCarrier() {
        for (List<Carrier> carriers : List.of(List.<Carrier>of(), List.of(Carrier.KT), List.of(Carrier.LGU, Carrier.SKT))) {
            assertThatThrownBy(() -> service.getPartners(null, null, null, null, null,
                    carriers, Grade.VIP, PageRequest.of(0, 15)))
                    .isInstanceOf(InvalidEnumException.class);
        }
    }

    @Test
    void membershipFilterIncludesVipKokAndKeepsFilteredPageTotals() {
        PageRequest pageable = PageRequest.of(1, 1);
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy basicOnly = policy(101L, benefit, Carrier.LGU, "우수 전용");
        BenefitCarrierPolicy vvip = policy(102L, benefit, Carrier.LGU, "VVIP·VIP콕 혜택");
        BenefitCarrierPolicy skt = policy(100L, benefit, Carrier.SKT, "SKT 혜택");
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq(null), eq(null), eq(List.of("")),
                eq(true), eq(List.of("LGU")), eq(true), eq(List.of("VVIP", "VIP콕")),
                eq("POPULARITY"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(partner), pageable, 3));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)))
                .thenReturn(List.of(vvip, skt, basicOnly));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(
                tier(basicOnly, Grade.BASIC, "우수 전용"), tier(skt, Grade.SKT_VIP, "SKT 할인"),
                tier(vvip, Grade.VIP, "VIP만 적용"), tier(vvip, Grade.VVIP, "VVIP 할인"),
                tier(vvip, Grade.VIP콕, "VIP콕 할인")));

        var result = service.getPartners(null, null, null, null, null,
                List.of(Carrier.LGU), Grade.VVIP, pageable);

        assertThat(result.getContent().get(0).getCarriers()).containsExactly(Carrier.LGU);
        assertThat(result.getContent().get(0).getPreviews()).singleElement().satisfies(preview -> {
            assertThat(preview.carrier()).isEqualTo(Carrier.LGU);
            assertThat(preview.benefitName()).isEqualTo("VVIP·VIP콕 혜택");
            assertThat(preview.tierBenefits()).extracting(tier -> tier.grade())
                    .containsExactly(Grade.VVIP, Grade.VIP콕);
        });
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getCurrentPage()).isEqualTo(1);
        assertThat(result.isHasNext()).isTrue();
    }

    @Test
    void scopedAllGradeOfferRemainsVisibleWithoutLegacyTierRows() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy policy = policy(101L, benefit, Carrier.SKT, "SKT 멤버십");
        policy.setUsageType(UsageType.ONLINE);
        policy.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("all-online", "온라인 포인트 사용", "원스토리 포인트 사용",
                        UsageType.ONLINE, List.of(), true, List.of("전자책"), List.of(), List.of(),
                        List.of(), List.of(), List.of())),
                com.itplace.userapi.benefit.dto.OfferResolution.SCOPED, null));
        PageRequest pageable = PageRequest.of(0, 10);
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq(null), eq(null), eq(List.of("")),
                eq(true), eq(List.of("SKT")), eq(true), eq(List.of("SKT_LITE")),
                eq("POPULARITY"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(partner), pageable, 1));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of());

        var result = service.getPartners(null, null, null, null, null, List.of(Carrier.SKT),
                Grade.SKT_LITE, pageable);

        assertThat(result.getContent()).singleElement().satisfies(item ->
                assertThat(item.getPreviews()).singleElement().satisfies(preview -> {
                    assertThat(preview.offers()).isEmpty();
                    assertThat(preview.summary()).satisfies(summary -> {
                        assertThat(summary.offerKey()).isEqualTo("all-online");
                        assertThat(summary.allGrades()).isTrue();
                        assertThat(summary.title()).isEqualTo("온라인 포인트 사용");
                    });
                    assertThat(preview.tierBenefits()).isEmpty();
                }));
    }

    @Test
    void singleChannelEligibleOfferWithoutGradeEvidenceRemainsVisibleWithoutInventedTier() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy policy = policy(101L, benefit, Carrier.KT, "KT 멤버십");
        policy.setUsageType(UsageType.BOTH);
        policy.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("channel-only", "제휴 혜택", "온라인/오프라인 이용",
                        UsageType.BOTH, List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                com.itplace.userapi.benefit.dto.OfferResolution.SINGLE, null));
        PageRequest pageable = PageRequest.of(0, 10);
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq("ONLINE"), eq(null), eq(List.of("")),
                eq(true), eq(List.of("KT")), eq(false), eq(List.of("")),
                eq("POPULARITY"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(partner), pageable, 1));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of());

        var result = service.getPartners(null, null, UsageType.ONLINE, null, null,
                List.of(Carrier.KT), null, pageable);

        assertThat(result.getContent()).singleElement().satisfies(item ->
                assertThat(item.getPreviews()).singleElement().satisfies(preview -> {
                    assertThat(preview.offers()).isEmpty();
                    assertThat(preview.summary().offerKey()).isEqualTo("channel-only");
                    assertThat(preview.tierBenefits()).isEmpty();
                }));
    }

    @Test
    void singleAllGradeOfferDoesNotTurnMixedSourceTextIntoGradeReward() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy policy = policy(101L, benefit, Carrier.KT, "KT 멤버십");
        policy.setUsageType(UsageType.BOTH);
        policy.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("all-grade", "공연 혜택", "VIP 무료 공연", UsageType.BOTH,
                        List.of(), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                com.itplace.userapi.benefit.dto.OfferResolution.SINGLE, null));
        PageRequest pageable = PageRequest.of(0, 10);
        when(partnerRepository.findBenefitPartners(eq(null), eq(null), eq(null), eq(null), eq(List.of("")),
                eq(true), eq(List.of("KT")), eq(true), eq(List.of("KT_WHITE")),
                eq("POPULARITY"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(partner), pageable, 1));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any()))
                .thenReturn(List.of(tier(policy, Grade.KT_VIP, "VIP 무료 공연")));

        var result = service.getPartners(null, null, null, null, null,
                List.of(Carrier.KT), Grade.KT_WHITE, pageable);

        assertThat(result.getContent()).singleElement().satisfies(item ->
                assertThat(item.getPreviews()).singleElement().satisfies(preview -> {
                    assertThat(preview.offers()).isEmpty();
                    assertThat(preview.summary().offerKey()).isEqualTo("all-grade");
                    assertThat(preview.tierBenefits()).isEmpty();
                }));
    }

    @Test
    void filteredDetailOnlyContainsMatchingCarrierPoliciesAndGrades() {
        Partner partner = partner();
        Benefit benefit = benefit(partner);
        BenefitCarrierPolicy lgu = policy(101L, benefit, Carrier.LGU, "LGU 할인");
        BenefitCarrierPolicy skt = policy(102L, benefit, Carrier.SKT, "SKT 할인");
        BenefitCarrierPolicy basicOnly = policy(103L, benefit, Carrier.LGU, "우수 전용");
        when(partnerRepository.findById(10L)).thenReturn(Optional.of(partner));
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(10L))).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)))
                .thenReturn(List.of(lgu, skt, basicOnly));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(
                tier(lgu, Grade.VIP, "VIP 할인"), tier(lgu, Grade.VIP콕, "VIP콕 할인"),
                tier(lgu, Grade.BASIC, "우수 할인"), tier(lgu, Grade.DIAMOND, "다이아몬드 할인"),
                tier(skt, Grade.SKT_VIP, "SKT 할인"),
                tier(basicOnly, Grade.BASIC, "우수 전용")));

        var result = service.getPartnerDetail(10L, MainCategory.BASIC_BENEFIT, null, Carrier.LGU, Grade.VIP);

        assertThat(result.getCarrierGroups()).singleElement().satisfies(group -> {
            assertThat(group.getCarrier()).isEqualTo(Carrier.LGU);
            assertThat(group.getBenefits()).singleElement().satisfies(item ->
                    assertThat(item.getTierBenefits()).extracting(tier -> tier.getGrade())
                            .containsExactly(Grade.VIP, Grade.VIP콕));
        });

        var diamondResult = service.getPartnerDetail(10L, MainCategory.BASIC_BENEFIT, null, Carrier.LGU, Grade.DIAMOND);
        assertThat(diamondResult.getCarrierGroups()).singleElement().satisfies(group ->
                assertThat(group.getBenefits()).singleElement().satisfies(item ->
                        assertThat(item.getTierBenefits()).extracting(tier -> tier.getGrade())
                                .containsExactly(Grade.DIAMOND)));
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
                .sourceUrl("https://carrier.example/detail")
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
