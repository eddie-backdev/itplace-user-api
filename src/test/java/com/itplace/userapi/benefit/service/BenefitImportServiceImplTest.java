package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportStatus;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.BenefitSnapshotImportState;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.BenefitSnapshotImportStateRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.reference.ReferenceDataService;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BenefitImportServiceImplTest {

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Mock
    private BenefitPolicyRepository benefitPolicyRepository;

    @Mock
    private BenefitSnapshotImportStateRepository benefitSnapshotImportStateRepository;

    @Mock
    private com.itplace.userapi.map.service.PartnerBenefitCacheService partnerBenefitCacheService;

    @Mock
    private BenefitCacheRefreshService benefitCacheRefreshService;

    @Mock
    private ReferenceDataService referenceDataService;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private BenefitImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BenefitImportServiceImpl(
                benefitRepository,
                partnerRepository,
                benefitCarrierPolicyRepository,
                carrierTierBenefitRepository,
                benefitPolicyRepository,
                benefitSnapshotImportStateRepository,
                partnerBenefitCacheService,
                benefitCacheRefreshService,
                referenceDataService,
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "expectedApiKey", "internal-key");
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(1);
        lenient().when(referenceDataService.isActiveProgram(any())).thenReturn(true);
        lenient().when(referenceDataService.isActiveCategory(any())).thenReturn(true);
        lenient().when(referenceDataService.isBenefitGrade(any(Carrier.class), any())).thenReturn(true);
        lenient().when(referenceDataService.allGradeMembers(Carrier.SKT)).thenReturn(List.of(Grade.SKT_LITE, Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP));
        lenient().when(referenceDataService.allGradeMembers(Carrier.KT)).thenReturn(List.of(Grade.KT_GENERAL, Grade.KT_WHITE, Grade.KT_SILVER, Grade.KT_GOLD, Grade.KT_VIP, Grade.KT_VVIP));
        lenient().when(referenceDataService.allGradeMembers(Carrier.LGU)).thenReturn(List.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.DIAMOND));
        lenient().when(benefitSnapshotImportStateRepository.findByCarrierForUpdate(any(Carrier.class)))
                .thenAnswer(invocation -> java.util.Optional.of(
                        new BenefitSnapshotImportState(invocation.getArgument(0), null)
                ));
        lenient().when(partnerRepository.findAllByPartnerNameIn(any())).thenReturn(List.of());
        lenient().when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(any(Carrier.class), any()))
                .thenReturn(List.of());
        lenient().when(benefitCarrierPolicyRepository.findAllByCarrier(any(Carrier.class))).thenReturn(List.of());
        lenient().when(benefitCarrierPolicyRepository.findLatestCrawledAtByCarrier(any(Carrier.class)))
                .thenReturn(java.util.Optional.empty());
        lenient().when(benefitPolicyRepository.findByCode(any(BenefitPolicyCode.class))).thenAnswer(invocation -> {
            BenefitPolicyCode code = invocation.getArgument(0);
            return java.util.Optional.of(BenefitPolicy.builder().code(code).name(code.name()).build());
        });
        lenient().when(benefitRepository.findAllByPartnerIdsAndCanonicalKeys(any(), any())).thenReturn(List.of());
        lenient().when(partnerRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<Partner> partners = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(7L);
            partners.forEach(partner -> {
                if (partner.getPartnerId() == null) {
                    partner.setPartnerId(sequence.getAndIncrement());
                }
            });
            return partners;
        });
        lenient().when(benefitRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<Benefit> benefits = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(11L);
            benefits.forEach(benefit -> {
                if (benefit.getBenefitId() == null) {
                    benefit.setBenefitId(sequence.getAndIncrement());
                }
            });
            return benefits;
        });
        lenient().when(benefitCarrierPolicyRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<BenefitCarrierPolicy> policies = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(31L);
            policies.forEach(policy -> {
                if (policy.getBenefitCarrierPolicyId() == null) {
                    policy.setBenefitCarrierPolicyId(sequence.getAndIncrement());
                }
            });
            return policies;
        });
        lenient().when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any()))
                .thenReturn(List.of());
        lenient().when(carrierTierBenefitRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        if (iterable instanceof List<T> list) {
            return list;
        }
        List<T> result = new java.util.ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Iterable<T>> iterableCaptor() {
        return ArgumentCaptor.forClass(Iterable.class);
    }

    @Test
    void importsExpandedCompoundTierLinksAndRetainsThemWhenSnapshotOrderChanges() {
        BenefitSnapshotImportRequest request = request();
        var item = request.getBenefits().get(0);
        var movie = importOffer("movie", "영화표 할인", "온라인 영화표 10% 할인", List.of("SKT_VIP"));
        var popcorn = importOffer("popcorn", "팝콘 할인", "팝콘 2천원 할인", List.of("SKT_VIP"));
        item.setConditions(importConditions(List.of(movie, popcorn)));
        item.setTierBenefits(List.of(tierWithOffer(Grade.SKT_VIP, "영화표 할인", "movie"),
                tierWithOffer(Grade.SKT_VIP, "팝콘 할인", "popcorn")));

        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit benefit = Benefit.builder().benefitId(11L).partner(partner).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(31L).carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1").benefit(benefit).build();
        CarrierTierBenefit movieTier = CarrierTierBenefit.builder().carrierTierBenefitId(40L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("영화표 할인").offerKey("movie").isAll(false).build();
        CarrierTierBenefit popcornTier = CarrierTierBenefit.builder().carrierTierBenefitId(41L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("팝콘 할인").offerKey("popcorn").isAll(false).build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(movieTier, popcornTier));

        service.importSnapshot(request, "internal-key");
        item.setTierBenefits(List.of(tierWithOffer(Grade.SKT_VIP, "팝콘 할인", "popcorn"),
                tierWithOffer(Grade.SKT_VIP, "영화표 할인", "movie")));
        clearInvocations(carrierTierBenefitRepository);
        service.importSnapshot(request, "internal-key");

        verify(carrierTierBenefitRepository, never()).saveAll(any());
        assertThat(movieTier.getOfferKey()).isEqualTo("movie");
        assertThat(popcornTier.getOfferKey()).isEqualTo("popcorn");
        assertThat(movieTier.getCarrierTierBenefitId()).isEqualTo(40L);
        assertThat(popcornTier.getCarrierTierBenefitId()).isEqualTo(41L);
        verify(carrierTierBenefitRepository, never()).deleteAll(any());
    }

    @Test
    void uniquelyUpgradesLegacyTierIdentityToResolvedOfferKey() {
        BenefitSnapshotImportRequest request = request();
        var item = request.getBenefits().get(0);
        item.setConditions(importConditions(List.of(importOffer("movie", "VIP 영화 할인", "VIP 영화 할인", List.of("SKT_VIP")))));
        item.setTierBenefits(List.of(tierWithOffer(Grade.SKT_VIP, "VIP 영화 할인", "movie")));
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit benefit = Benefit.builder().benefitId(11L).partner(partner).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(31L).carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1").benefit(benefit).build();
        CarrierTierBenefit old = CarrierTierBenefit.builder().carrierTierBenefitId(52L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("VIP 영화 할인").isAll(false).offerKey("offer-1").build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(old));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> saved = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(saved.capture());
        assertThat(toList(saved.getValue())).singleElement().satisfies(tier -> {
            assertThat(tier).isSameAs(old);
            assertThat(tier.getCarrierTierBenefitId()).isEqualTo(52L);
            assertThat(tier.getOfferKey()).isEqualTo("movie");
        });
        verify(carrierTierBenefitRepository, never()).deleteAll(any());
    }

    @Test
    void validatesGroupedTierEvidenceLineByLineAndRejectsWrongAmountOrNegativeClause() {
        BenefitSnapshotImportRequest request = request();
        var item = request.getBenefits().get(0);
        item.setConditions(importConditions(List.of(
                importOffer("grouped", "VIP 혜택", "VIP 10% 할인<br>VIP 포인트 적립", List.of("SKT_VIP")),
                importOffer("space", "공백 혜택", "VIP\u00a010% 할인", List.of("SKT_VIP")),
                importOffer("choice", "VIP 초이스 — VIP",
                        "[VIP 초이스]\n▶대상: VIP\n- 혜택: 밀리의 서재 3개월 무료", List.of("SKT_VIP")))));
        item.setTierBenefits(List.of(
                tierWithOffer(Grade.SKT_VIP, "VIP 10% 할인\nVIP 포인트 적립", "grouped"),
                tierWithOffer(Grade.SKT_VIP, "VIP 20% 할인\nVIP 포인트 적립", "grouped"),
                tierWithOffer(Grade.SKT_VIP, "VIP 10% 할인\nVIP 포인트 사용 불가", "grouped"),
                tierWithOffer(Grade.SKT_VIP, "VIP 10% 할인", "space"),
                tierWithOffer(Grade.SKT_VIP, "[VIP 초이스]밀리의 서재 3개월 무료", "choice"),
                tierWithOffer(Grade.SKT_VIP, "[VVIP 초이스]밀리의 서재 3개월 무료", "choice"),
                tierWithOffer(Grade.SKT_VIP, "[VIP 초이스]밀리의 서재 1개월 무료", "choice")));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> saved = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(saved.capture());
        assertThat(toList(saved.getValue())).extracting(CarrierTierBenefit::getOfferKey)
                .containsExactly("grouped", null, null, "space", "choice", null, null);
    }

    @Test
    void ambiguousPositionKeyContentDoesNotRebindRowsAcrossOffers() {
        BenefitSnapshotImportRequest request = request();
        var item = request.getBenefits().get(0);
        item.setConditions(importConditions(List.of(importOffer("first", "공통 혜택", "공통 혜택", List.of("SKT_VIP")),
                importOffer("second", "공통 혜택", "공통 혜택", List.of("SKT_VIP")))));
        item.setTierBenefits(List.of(tierWithOffer(Grade.SKT_VIP, "공통 혜택", "first"),
                tierWithOffer(Grade.SKT_VIP, "공통 혜택", "second")));
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit benefit = Benefit.builder().benefitId(11L).partner(partner).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(31L).carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1").benefit(benefit).build();
        CarrierTierBenefit oldFirst = CarrierTierBenefit.builder().carrierTierBenefitId(61L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("공통 혜택").isAll(false).offerKey("offer-1").build();
        CarrierTierBenefit oldSecond = CarrierTierBenefit.builder().carrierTierBenefitId(62L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("공통 혜택").isAll(false).offerKey("offer-2").build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(oldFirst, oldSecond));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> saved = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(saved.capture());
        assertThat(toList(saved.getValue())).extracting(CarrierTierBenefit::getOfferKey)
                .containsExactly("first", "second");
        assertThat(toList(saved.getValue())).doesNotContain(oldFirst, oldSecond);
        verify(carrierTierBenefitRepository).deleteAll(argThat(rows -> toList(rows).size() == 2 && toList(rows).containsAll(List.of(oldFirst, oldSecond))));
    }

    @Test
    void sameContextAcrossDifferentOffersDoesNotCreateCrossOfferAllGradeCoverage() {
        BenefitSnapshotImportRequest request = request();
        var item = request.getBenefits().get(0);
        item.setConditions(importConditions(List.of(
                importOffer("first", "공통 혜택", "공통 혜택", List.of("SKT_VIP", "SKT_GOLD")),
                importOffer("second", "공통 혜택", "공통 혜택", List.of("SKT_SILVER", "SKT_LITE")))));
        item.setTierBenefits(List.of(
                tierWithOffer(Grade.SKT_VIP, "공통 혜택", "first"), tierWithOffer(Grade.SKT_GOLD, "공통 혜택", "first"),
                tierWithOffer(Grade.SKT_SILVER, "공통 혜택", "second"), tierWithOffer(Grade.SKT_LITE, "공통 혜택", "second"),
                tierWithOffer(Grade.SKT_VIP, "공통 혜택", "missing-offer"),
                tierWithOffer(Grade.SKT_VIP, "공통 혜택 + 다른 조건", "first")));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> saved = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(saved.capture());
        assertThat(toList(saved.getValue())).hasSize(6).allSatisfy(tier -> assertThat(tier.getIsAll()).isFalse());
        assertThat(toList(saved.getValue())).extracting(CarrierTierBenefit::getOfferKey)
                .containsExactly("first", "first", "second", "second", null, null);
    }

    private com.itplace.userapi.benefit.dto.BenefitOffer importOffer(String key, String title, String source, List<String> grades) {
        return new com.itplace.userapi.benefit.dto.BenefitOffer(key, title, source, UsageType.BOTH, grades, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private com.itplace.userapi.benefit.dto.BenefitConditions importConditions(
            List<com.itplace.userapi.benefit.dto.BenefitOffer> offers) {
        return new com.itplace.userapi.benefit.dto.BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                offers, com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null);
    }

    private BenefitSnapshotImportRequest.TierBenefitSnapshot tierWithOffer(Grade grade, String context, String offerKey) {
        BenefitSnapshotImportRequest.TierBenefitSnapshot tier = tier(grade, context, false);
        tier.setOfferKey(offerKey);
        return tier;
    }

    @Test
    void resolvesEachBenefitPolicyCodeOncePerSnapshot() {
        var request = request();
        var second = request().getBenefits().get(0);
        second.setSourceKey("skt-benefit-2");
        second.setBenefitName("두번째 혜택");
        request.setBenefits(List.of(request.getBenefits().get(0), second));
        service.importSnapshot(request, "internal-key");
        verify(benefitPolicyRepository).findByCode(BenefitPolicyCode.DAILY_ONCE);
    }

    @Test
    void importsSanitizedCarrierScopedSnapshotWithoutMembershipArtifacts() {
        BenefitSnapshotImportRequest request = request();
        BenefitSnapshotImportState importState = new BenefitSnapshotImportState(Carrier.SKT, null);
        when(benefitSnapshotImportStateRepository.findByCarrierForUpdate(Carrier.SKT))
                .thenReturn(java.util.Optional.of(importState));

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getReceivedCount()).isEqualTo(1);
        assertThat(response.getTierBenefitCount()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(BenefitSnapshotImportStatus.APPLIED);
        assertThat(response.getLastAppliedCrawledAt()).isEqualTo(request.getCrawledAt());
        assertThat(importState.getLastAppliedCrawledAt()).isEqualTo(request.getCrawledAt());

        ArgumentCaptor<Iterable<Benefit>> benefitCaptor = iterableCaptor();
        verify(benefitRepository).saveAll(benefitCaptor.capture());
        Benefit imported = toList(benefitCaptor.getValue()).get(0);
        assertThat(imported.getCanonicalKey()).isEqualTo("7:할인혜택");
        assertThat(imported.getActive()).isTrue();

        ArgumentCaptor<Iterable<Partner>> partnerCaptor = iterableCaptor();
        verify(partnerRepository).saveAll(partnerCaptor.capture());
        Partner importedPartner = toList(partnerCaptor.getValue()).get(0);
        assertThat(importedPartner.getCategory()).isEqualTo("푸드");
        assertThat(importedPartner.getImage()).isEqualTo("https://images.itplace.click/img/spicus/logo.png");

        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policyCaptor = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policyCaptor.capture());
        BenefitCarrierPolicy policy = toList(policyCaptor.getValue()).get(0);
        assertThat(policy.getBenefit()).isSameAs(imported);
        assertThat(policy.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(policy.getSourceKey()).isEqualTo("skt-benefit-1");
        assertThat(policy.getSourceUrl()).isEqualTo("https://source.example/skt-benefit-1");
        assertThat(policy.getUrl()).isEqualTo("https://benefit.example/skt-benefit-1");
        assertThat(policy.getCarrierBenefitName()).isEqualTo("할인 혜택");
        assertThat(policy.getMembershipOptionType()).isEqualTo(MembershipOptionType.UNKNOWN);
        assertThat(policy.getActive()).isTrue();
        assertThat(policy.getBenefitPolicy().getCode()).isEqualTo(BenefitPolicyCode.DAILY_ONCE);

        ArgumentCaptor<Iterable<CarrierTierBenefit>> carrierTierCaptor = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(carrierTierCaptor.capture());
        List<CarrierTierBenefit> carrierTiers = toList(carrierTierCaptor.getValue());
        assertThat(carrierTiers).hasSize(1);
        assertThat(carrierTiers.get(0).getBenefitCarrierPolicy()).isSameAs(policy);
        assertThat(carrierTiers.get(0).getGrade()).isEqualTo(Grade.SKT_VIP);
        assertThat(carrierTiers.get(0).getIsAll()).isFalse();

        verify(partnerRepository, never()).findByPartnerName(any(String.class));
    }

    @Test
    void importsMixedGradeContextsWithoutTrustingLegacyAllFlags() {
        BenefitSnapshotImportRequest request = request();
        BenefitSnapshotImportRequest.BenefitSnapshotItem item = request.getBenefits().get(0);
        item.setTierBenefits(List.of(
                tier(Grade.SKT_VIP, "15% 할인", true),
                tier(Grade.SKT_GOLD, "15% 할인", true),
                tier(Grade.SKT_SILVER, "5% 할인", true)
        ));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> carrierTierCaptor = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(carrierTierCaptor.capture());
        assertThat(toList(carrierTierCaptor.getValue())).extracting(CarrierTierBenefit::getIsAll)
                .containsExactly(false, false, false);
    }

    @Test
    void keepsKnownMetadataWhenLaterSnapshotOmitsIt() {
        BenefitSnapshotImportRequest request = request();
        request.getBenefits().get(0).setBenefitName("GS25 할인형");
        BenefitCarrierPolicy existing = BenefitCarrierPolicy.builder()
                .benefit(Benefit.builder().benefitId(55L).partner(Partner.builder().partnerId(7L).build()).build())
                .carrier(Carrier.SKT).sourceKey("skt-benefit-1")
                .membershipOptionType(MembershipOptionType.POINT_ACCRUAL)
                .sourceOptionLabel("적립형").build();
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(existing));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policies = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policies.capture());
        BenefitCarrierPolicy policy = toList(policies.getValue()).get(0);
        assertThat(policy.getMembershipOptionType()).isEqualTo(MembershipOptionType.POINT_ACCRUAL);
        assertThat(policy.getSourceOptionLabel()).isEqualTo("적립형");
    }

    @Test
    void explicitNotApplicableClearsPreviouslyKnownOptionLabel() {
        BenefitSnapshotImportRequest request = request();
        request.getBenefits().get(0).setMembershipOptionType(MembershipOptionType.NOT_APPLICABLE);
        BenefitCarrierPolicy existing = BenefitCarrierPolicy.builder()
                .benefit(Benefit.builder().benefitId(55L).partner(Partner.builder().partnerId(7L).build()).build())
                .carrier(Carrier.SKT).sourceKey("skt-benefit-1")
                .membershipOptionType(MembershipOptionType.DISCOUNT)
                .sourceOptionLabel("할인형").build();
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(existing));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policies = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policies.capture());
        BenefitCarrierPolicy policy = toList(policies.getValue()).get(0);
        assertThat(policy.getMembershipOptionType()).isEqualTo(MembershipOptionType.NOT_APPLICABLE);
        assertThat(policy.getSourceOptionLabel()).isNull();
    }

    @Test
    void infersLegacySktOptionFromTrailingTypeLabel() {
        BenefitSnapshotImportRequest request = request();
        request.getBenefits().get(0).setBenefitName("GS25 적립형");
        service.importSnapshot(request, "internal-key");
        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policies = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policies.capture());
        BenefitCarrierPolicy policy = toList(policies.getValue()).get(0);
        assertThat(policy.getMembershipOptionType()).isEqualTo(MembershipOptionType.POINT_ACCRUAL);
        assertThat(policy.getSourceOptionLabel()).isEqualTo("적립형");
    }

    @Test
    void unchangedTiersRetainDatabaseIdentity() {
        BenefitSnapshotImportRequest request = request();
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit existingBenefit = Benefit.builder()
                .benefitId(11L)
                .partner(partner)
                .canonicalKey("7:기존혜택")
                .build();
        BenefitCarrierPolicy existingPolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(31L)
                .carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1")
                .benefit(existingBenefit)
                .build();
        CarrierTierBenefit existingTier = CarrierTierBenefit.builder()
                .benefitCarrierPolicy(existingPolicy)
                .grade(Grade.SKT_VIP)
                .carrierTierBenefitId(99L)
                .context("VIP 20% 할인")
                .isAll(false)
                .build();

        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(existingPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(existingPolicy)))
                .thenReturn(List.of(existingTier));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<Benefit>> benefitCaptor = iterableCaptor();
        verify(benefitRepository).saveAll(benefitCaptor.capture());
        assertThat(toList(benefitCaptor.getValue()).get(0)).isSameAs(existingBenefit);
        assertThat(existingBenefit.getBenefitName()).isEqualTo("할인 혜택");

        verify(carrierTierBenefitRepository, never()).deleteAll(any());
        verify(carrierTierBenefitRepository, never()).saveAll(any());
        assertThat(existingTier.getCarrierTierBenefitId()).isEqualTo(99L);
    }

    @Test
    void changedUniqueGradeTierUpdatesInPlaceAndKeepsDatabaseIdentity() {
        BenefitSnapshotImportRequest request = request();
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit existingBenefit = Benefit.builder().benefitId(11L).partner(partner).build();
        BenefitCarrierPolicy existingPolicy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(31L)
                .carrier(Carrier.SKT).sourceKey("skt-benefit-1").benefit(existingBenefit).build();
        CarrierTierBenefit existingTier = CarrierTierBenefit.builder().carrierTierBenefitId(99L)
                .benefitCarrierPolicy(existingPolicy).grade(Grade.SKT_VIP).context("이전 설명").isAll(false).build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(existingPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(existingTier));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> captor = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(captor.capture());
        assertThat(toList(captor.getValue())).singleElement().satisfies(tier -> {
            assertThat(tier).isSameAs(existingTier);
            assertThat(tier.getCarrierTierBenefitId()).isEqualTo(99L);
            assertThat(tier.getContext()).isEqualTo("VIP 20% 할인");
        });
        verify(carrierTierBenefitRepository, never()).deleteAll(any());
    }

    @Test
    void reusesExistingPolicyBenefitWhenSourceKeyExists() {
        BenefitSnapshotImportRequest request = request();
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit existingBenefit = Benefit.builder()
                .benefitId(11L)
                .partner(partner)
                .canonicalKey("7:기존혜택")
                .build();
        BenefitCarrierPolicy existingPolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(31L)
                .carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1")
                .benefit(existingBenefit)
                .build();
        CarrierTierBenefit existingTier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(98L)
                .benefitCarrierPolicy(existingPolicy)
                .grade(Grade.SKT_VIP)
                .context("기존")
                .build();

        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(Carrier.SKT, List.of("skt-benefit-1")))
                .thenReturn(List.of(existingPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(existingPolicy)))
                .thenReturn(List.of(existingTier));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<Benefit>> benefitCaptor = iterableCaptor();
        verify(benefitRepository).saveAll(benefitCaptor.capture());
        assertThat(toList(benefitCaptor.getValue()).get(0)).isSameAs(existingBenefit);
        assertThat(existingBenefit.getBenefitName()).isEqualTo("할인 혜택");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> tiers = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(tiers.capture());
        assertThat(toList(tiers.getValue())).singleElement().satisfies(tier -> {
            assertThat(tier).isSameAs(existingTier);
            assertThat(tier.getCarrierTierBenefitId()).isEqualTo(98L);
            assertThat(tier.getContext()).isEqualTo("VIP 20% 할인");
        });
        verify(carrierTierBenefitRepository, never()).deleteAll(any());
    }

    @Test
    void mergesCarrierSpecificPartnerAliasIntoCanonicalPartner() {
        BenefitSnapshotImportRequest request = request();
        BenefitSnapshotImportRequest.BenefitSnapshotItem item = request.getBenefits().get(0);
        item.setPartnerName("파파존스피자");
        item.setPartnerAliases(List.of("파파존스", "파파존스피자"));
        Partner existingAlias = Partner.builder()
                .partnerId(95L)
                .partnerName("파파존스")
                .image("https://existing.example/papa.png")
                .build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("파파존스피자", "파파존스")))
                .thenReturn(List.of(existingAlias));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<Partner>> partnerCaptor = iterableCaptor();
        verify(partnerRepository).saveAll(partnerCaptor.capture());
        List<Partner> savedPartners = toList(partnerCaptor.getValue());
        assertThat(savedPartners).containsExactly(existingAlias);
        assertThat(existingAlias.getPartnerName()).isEqualTo("파파존스피자");

        ArgumentCaptor<Iterable<Benefit>> benefitCaptor = iterableCaptor();
        verify(benefitRepository).saveAll(benefitCaptor.capture());
        assertThat(toList(benefitCaptor.getValue()).get(0).getPartner()).isSameAs(existingAlias);
    }

    @Test
    void inactivatesActiveCarrierPoliciesMissingFromFullSnapshot() {
        BenefitCarrierPolicy missing = BenefitCarrierPolicy.builder()
                .benefit(Benefit.builder().mainCategory(MainCategory.BASIC_BENEFIT)
                        .partner(Partner.builder().partnerId(88L).build()).build())
                .benefitCarrierPolicyId(99L)
                .carrier(Carrier.SKT)
                .sourceKey("skt-disappeared")
                .active(true)
                .build();
        when(benefitCarrierPolicyRepository.findAllByCarrier(Carrier.SKT)).thenReturn(List.of(missing));

        service.importSnapshot(request(), "internal-key");

        assertThat(missing.getActive()).isFalse();
        assertThat(missing.getLastCrawledAt()).isNotNull();
        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policyCaptor = iterableCaptor();
        verify(benefitCarrierPolicyRepository, times(2)).saveAll(policyCaptor.capture());
        assertThat(toList(policyCaptor.getAllValues().get(1))).containsExactly(missing);
        verify(partnerBenefitCacheService).invalidateAfterCommit(List.of(7L, 88L));
    }

    @Test
    void inactivatesMissingPoliciesWithoutPartnerAndInvalidatesOnlyKnownPartnerIds() {
        BenefitCarrierPolicy orphan = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(91L)
                .benefit(Benefit.builder().benefitId(81L).mainCategory(MainCategory.BASIC_BENEFIT).build())
                .carrier(Carrier.SKT).sourceKey("orphan-policy").active(true).build();
        BenefitCarrierPolicy partnerWithoutId = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(92L)
                .benefit(Benefit.builder().benefitId(82L).mainCategory(MainCategory.BASIC_BENEFIT)
                        .partner(Partner.builder().build()).build())
                .carrier(Carrier.SKT).sourceKey("partner-without-id").active(true).build();
        BenefitCarrierPolicy knownPartner = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(93L)
                .benefit(Benefit.builder().benefitId(83L).mainCategory(MainCategory.BASIC_BENEFIT)
                        .partner(Partner.builder().partnerId(88L).build()).build())
                .carrier(Carrier.SKT).sourceKey("known-partner").active(true).build();
        BenefitCarrierPolicy vipCock = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(94L)
                .benefit(Benefit.builder().benefitId(84L).mainCategory(MainCategory.VIP_COCK).build())
                .carrier(Carrier.SKT).sourceKey("vip-cock").active(true).build();
        List<BenefitCarrierPolicy> missing = List.of(orphan, partnerWithoutId, knownPartner, vipCock);
        when(benefitCarrierPolicyRepository.findAllByCarrier(Carrier.SKT)).thenReturn(missing);

        BenefitSnapshotImportResponse response = service.importSnapshot(request(), "internal-key");

        assertThat(response.getStatus()).isEqualTo(BenefitSnapshotImportStatus.APPLIED);
        assertThat(missing.subList(0, 3)).allSatisfy(policy -> {
            assertThat(policy.getActive()).isFalse();
            assertThat(policy.getLastCrawledAt()).isNotNull();
        });
        assertThat(vipCock.getActive()).isTrue();
        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policyCaptor = iterableCaptor();
        verify(benefitCarrierPolicyRepository, times(2)).saveAll(policyCaptor.capture());
        assertThat(toList(policyCaptor.getAllValues().get(1))).containsExactly(orphan, partnerWithoutId, knownPartner);
        verify(partnerBenefitCacheService).invalidateAfterCommit(List.of(7L, 88L));
    }

    @Test
    void ignoresDuplicateSnapshotWithoutChangingBenefits() {
        BenefitSnapshotImportRequest request = request();
        request.setCrawledAt(LocalDateTime.of(2026, 8, 14, 3, 0, 0, 123_456_789));
        LocalDateTime alreadyAppliedAt = LocalDateTime.of(2026, 8, 14, 3, 0, 0, 123_456_000);
        when(benefitSnapshotImportStateRepository.findByCarrierForUpdate(Carrier.SKT))
                .thenReturn(java.util.Optional.of(
                        new BenefitSnapshotImportState(Carrier.SKT, alreadyAppliedAt)
                ));

        when(partnerRepository.findAll()).thenReturn(List.of(Partner.builder().partnerId(7L).build(),
                Partner.builder().partnerId(88L).build()));
        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        verify(partnerBenefitCacheService).invalidateAfterCommit(List.of(7L, 88L));
        assertThat(response.getStatus()).isEqualTo(BenefitSnapshotImportStatus.DUPLICATE);
        assertThat(response.getReceivedCount()).isEqualTo(1);
        assertThat(response.getUpsertedBenefitCount()).isZero();
        assertThat(response.getLastAppliedCrawledAt()).isEqualTo(alreadyAppliedAt);
        verify(partnerRepository, never()).saveAll(any());
        verify(benefitRepository, never()).saveAll(any());
        verify(benefitCarrierPolicyRepository, never()).saveAll(any());
    }

    @Test
    void ignoresStaleSnapshotWithoutInactivatingCurrentBenefits() {
        BenefitSnapshotImportRequest request = request();
        LocalDateTime lastAppliedAt = request.getCrawledAt().plusMinutes(1);
        when(benefitSnapshotImportStateRepository.findByCarrierForUpdate(Carrier.SKT))
                .thenReturn(java.util.Optional.of(
                        new BenefitSnapshotImportState(Carrier.SKT, lastAppliedAt)
                ));

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getStatus()).isEqualTo(BenefitSnapshotImportStatus.STALE);
        assertThat(response.getUpsertedBenefitCount()).isZero();
        assertThat(response.getLastAppliedCrawledAt()).isEqualTo(lastAppliedAt);
        verify(benefitCarrierPolicyRepository, never()).findAllByCarrier(any(Carrier.class));
        verify(partnerRepository, never()).saveAll(any());
        verify(benefitRepository, never()).saveAll(any());
        verify(benefitCarrierPolicyRepository, never()).saveAll(any());
    }

    @Test
    void rejectsSnapshotOlderThanPolicyDataDuringBlueGreenTransition() {
        BenefitSnapshotImportRequest request = request();
        LocalDateTime policyCrawledAt = request.getCrawledAt().plusMinutes(1);
        when(benefitCarrierPolicyRepository.findLatestCrawledAtByCarrier(Carrier.SKT))
                .thenReturn(java.util.Optional.of(policyCrawledAt));

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getStatus()).isEqualTo(BenefitSnapshotImportStatus.STALE);
        assertThat(response.getLastAppliedCrawledAt()).isEqualTo(policyCrawledAt);
        verify(partnerRepository, never()).saveAll(any());
        verify(benefitRepository, never()).saveAll(any());
        verify(benefitCarrierPolicyRepository, never()).saveAll(any());
    }

    @Test
    void rejectsMissingInternalApiKey() {
        assertThatThrownBy(() -> service.importSnapshot(request(), "wrong-key"))
                .isInstanceOf(BenefitImportUnauthorizedException.class)
                .extracting("code")
                .isEqualTo(BenefitCode.BENEFIT_IMPORT_UNAUTHORIZED);
    }


    @Test
    void importRequestAcceptsAdminSnapshotFieldAliases() throws Exception {
        String json = """
                {
                  "carrier":"SKT",
                  "exportedAt":"2026-04-29T21:00:00",
                  "benefits":[{
                    "sourceKey":"skt-benefit-1",
                    "sourceUrl":"https://source.example",
                    "partnerName":"제휴사",
                    "partnerImageUrl":"https://image.example/logo.png",
                    "partnerCategory":"푸드",
                    "mainCategory":"BASIC_BENEFIT",
                    "benefitName":"할인 혜택",
                    "type":"DISCOUNT",
                    "usageType":"ONLINE",
                    "url":"https://benefit.example",
                    "sourceEvidenceId":901,
                    "sourceCapturedAt":"2026-10-19T07:30:00Z",
                    "sourceLimitText":"일 1회, 월 2회",
                    "conditions":{
                      "products":["아이스컵"],
                      "availability":["전국 매장"],
                      "limits":["일 1회","월 2회"],
                      "usage":["바코드 제시"],
                      "exclusions":["일부 매장 제외"]
                    },
                    "tiers":[{"grade":"SKT_VIP","context":"VIP 20% 할인","isAll":false}]
                  }]
                }
                """;

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BenefitSnapshotImportRequest parsed = objectMapper.readValue(json, BenefitSnapshotImportRequest.class);

        assertThat(parsed.getCrawledAt()).isNotNull();
        assertThat(parsed.getBenefits().get(0).getSourceUrl()).isEqualTo("https://source.example");
        assertThat(parsed.getBenefits().get(0).getUrl()).isEqualTo("https://benefit.example");
        assertThat(parsed.getBenefits().get(0).getPartnerImage()).isEqualTo("https://image.example/logo.png");
        assertThat(parsed.getBenefits().get(0).getBenefitPolicyCode()).isEqualTo(BenefitPolicyCode.UNKNOWN);
        assertThat(parsed.getBenefits().get(0).getTierBenefits()).hasSize(1);
        assertThat(parsed.getBenefits().get(0).getTierBenefits().get(0).getGrade()).isEqualTo(Grade.SKT_VIP);
        assertThat(parsed.getBenefits().get(0).getSourceEvidenceId()).isEqualTo(901L);
        assertThat(parsed.getBenefits().get(0).getSourceCapturedAt()).isEqualTo(Instant.parse("2026-10-19T07:30:00Z"));
        assertThat(parsed.getBenefits().get(0).getSourceLimitText()).isEqualTo("일 1회, 월 2회");
        assertThat(parsed.getBenefits().get(0).getConditions().limits()).containsExactly("일 1회", "월 2회");

        service.importSnapshot(parsed, "internal-key");

        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policies = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policies.capture());
        BenefitCarrierPolicy imported = toList(policies.getValue()).get(0);
        assertThat(imported.getSourceEvidenceId()).isEqualTo(901L);
        assertThat(imported.getSourceCapturedAt()).isEqualTo(Instant.parse("2026-10-19T07:30:00Z"));
        assertThat(imported.getSourceLimitText()).isEqualTo("일 1회, 월 2회");
        assertThat(imported.getConditions()).isEqualTo(parsed.getBenefits().get(0).getConditions());
    }

    @Test
    void importsLguDiamondAsSourceBenefitGrade() {
        BenefitSnapshotImportRequest request = request();
        request.setCarrier(Carrier.LGU);
        BenefitSnapshotImportRequest.BenefitSnapshotItem item = request.getBenefits().get(0);
        item.setTierBenefits(List.of(tier(Grade.DIAMOND, "다이아몬드 전용 할인", false)));

        service.importSnapshot(request, "internal-key");

        ArgumentCaptor<Iterable<CarrierTierBenefit>> tiers = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(tiers.capture());
        assertThat(toList(tiers.getValue())).singleElement().satisfies(tier -> {
            assertThat(tier.getGrade()).isEqualTo(Grade.DIAMOND);
            assertThat(tier.getIsAll()).isFalse();
        });
    }

    @Test
    void mixedSnapshotAppliesValidRowsAndPreservesExistingHeldRowWithoutEvidence() {
        BenefitSnapshotImportRequest request = request();
        var held = new BenefitSnapshotImportRequest.BenefitSnapshotItem();
        held.setSourceKey("held-existing");
        held.setPartnerName("제휴사");
        held.setMainCategory(MainCategory.BASIC_BENEFIT);
        held.setBenefitName("unverified changed text");
        held.setType(BenefitType.DISCOUNT);
        held.setUsageType(UsageType.ONLINE);
        held.setSourceUrl("https://unverified.example/new");
        held.setPublicationStatus(com.itplace.userapi.benefit.entity.enums.PublicationStatus.HELD);
        held.setHoldReason("source unavailable");
        held.setTierBenefits(List.of(tier(Grade.SKT_VIP, "unverified tier", false)));
        request.setBenefits(new java.util.ArrayList<>(request.getBenefits()));
        request.getBenefits().add(held);

        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit oldBenefit = Benefit.builder().benefitId(90L).partner(partner)
                .mainCategory(MainCategory.BASIC_BENEFIT).benefitName("verified old title")
                .canonicalKey("7:verifiedoldtitle").active(true).build();
        BenefitCarrierPolicy heldPolicy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(91L)
                .benefit(oldBenefit).carrier(Carrier.SKT).sourceKey("held-existing").active(true)
                .sourceEvidenceId(77L).sourceCapturedAt(Instant.parse("2026-10-20T00:00:00Z"))
                .sourceUrl("https://verified.example/old").sourceLimitText("old limit")
                .conditions(new BenefitConditions(List.of("old source"), List.of(), List.of("old limit"), List.of(), List.of()))
                .build();
        CarrierTierBenefit oldTier = CarrierTierBenefit.builder().carrierTierBenefitId(92L)
                .benefitCarrierPolicy(heldPolicy).grade(Grade.SKT_VIP).context("verified old tier").build();

        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(
                Carrier.SKT, List.of("skt-benefit-1", "held-existing"))).thenReturn(List.of(heldPolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(oldTier));

        service.importSnapshot(request, "internal-key");

        assertThat(heldPolicy.getPublicationStatus()).isEqualTo(com.itplace.userapi.benefit.entity.enums.PublicationStatus.HELD);
        assertThat(heldPolicy.getActive()).isFalse();
        assertThat(heldPolicy.getSourceUrl()).isEqualTo("https://verified.example/old");
        assertThat(heldPolicy.getSourceEvidenceId()).isEqualTo(77L);
        assertThat(heldPolicy.getSourceLimitText()).isEqualTo("old limit");
        assertThat(oldBenefit.getBenefitName()).isEqualTo("verified old title");
        assertThat(oldTier.getContext()).isEqualTo("verified old tier");
        verify(benefitRepository).saveAll(any());
        verify(carrierTierBenefitRepository).saveAll(any());
    }

    @Test
    void archivedSnapshotRowRemainsPrivateAndPreservesHistoricalContent() {
        BenefitSnapshotImportRequest request = request();
        var archived = new BenefitSnapshotImportRequest.BenefitSnapshotItem();
        archived.setSourceKey("archived-existing");
        archived.setPartnerName("제휴사");
        archived.setMainCategory(MainCategory.BASIC_BENEFIT);
        archived.setBenefitName("changed incoming title");
        archived.setType(BenefitType.DISCOUNT);
        archived.setUsageType(UsageType.ONLINE);
        archived.setPublicationStatus(com.itplace.userapi.benefit.entity.enums.PublicationStatus.APPROVED);
        archived.setSourceUrl("https://unverified.example/new");
        archived.setTierBenefits(List.of(tier(Grade.SKT_VIP, "changed tier", false)));
        request.setBenefits(new java.util.ArrayList<>(request.getBenefits()));
        request.getBenefits().add(archived);

        Instant reviewedAt = Instant.parse("2026-10-21T00:00:00Z");
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit oldBenefit = Benefit.builder().benefitId(90L).partner(partner)
                .mainCategory(MainCategory.BASIC_BENEFIT).benefitName("verified historic title")
                .canonicalKey("7:verifiedhistorictitle").active(true).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(91L)
                .benefit(oldBenefit).carrier(Carrier.SKT).sourceKey("archived-existing").active(false)
                .publicationStatus(com.itplace.userapi.benefit.entity.enums.PublicationStatus.ARCHIVED)
                .holdReason("reviewed duplicate; replaced by policy 123")
                .publicationReviewedAt(reviewedAt).sourceEvidenceId(77L)
                .sourceCapturedAt(Instant.parse("2026-10-20T00:00:00Z"))
                .sourceUrl("https://verified.example/old").sourceLimitText("historic limit")
                .type(BenefitType.FREE).description("historic description").manual("historic manual")
                .usageType(UsageType.OFFLINE).url("https://verified.example/manual")
                .membershipOptionType(MembershipOptionType.POINT_ACCRUAL).sourceOptionLabel("적립형")
                .carrierBenefitName("historic carrier name")
                .conditions(new BenefitConditions(List.of("old source"), List.of(), List.of("historic limit"), List.of(), List.of()))
                .build();
        CarrierTierBenefit oldTier = CarrierTierBenefit.builder().carrierTierBenefitId(92L)
                .benefitCarrierPolicy(policy).grade(Grade.SKT_VIP).context("historic tier").build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(
                Carrier.SKT, List.of("skt-benefit-1", "archived-existing"))).thenReturn(List.of(policy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of(oldTier));

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(policy.getPublicationStatus())
                .isEqualTo(com.itplace.userapi.benefit.entity.enums.PublicationStatus.ARCHIVED);
        assertThat(policy.getActive()).isFalse();
        assertThat(policy.getHoldReason()).isEqualTo("reviewed duplicate; replaced by policy 123");
        assertThat(policy.getPublicationReviewedAt()).isEqualTo(reviewedAt);
        assertThat(policy.getSourceUrl()).isEqualTo("https://verified.example/old");
        assertThat(policy.getSourceEvidenceId()).isEqualTo(77L);
        assertThat(policy.getSourceLimitText()).isEqualTo("historic limit");
        assertThat(policy.getCarrierBenefitName()).isEqualTo("historic carrier name");
        assertThat(policy.getType()).isEqualTo(BenefitType.FREE);
        assertThat(policy.getDescription()).isEqualTo("historic description");
        assertThat(policy.getManual()).isEqualTo("historic manual");
        assertThat(policy.getUsageType()).isEqualTo(UsageType.OFFLINE);
        assertThat(policy.getUrl()).isEqualTo("https://verified.example/manual");
        assertThat(policy.getMembershipOptionType()).isEqualTo(MembershipOptionType.POINT_ACCRUAL);
        assertThat(policy.getSourceOptionLabel()).isEqualTo("적립형");
        assertThat(oldBenefit.getBenefitName()).isEqualTo("verified historic title");
        assertThat(oldTier.getContext()).isEqualTo("historic tier");
        assertThat(response.getTierBenefitCount()).isEqualTo(1);
        verify(carrierTierBenefitRepository).saveAll(argThat(items ->
                java.util.stream.StreamSupport.stream(items.spliterator(), false).noneMatch(item -> item == oldTier)));
    }

    @Test
    void firstArchivedSnapshotImportPersistsContentAndTiersButKeepsPolicyPrivate() {
        BenefitSnapshotImportRequest incoming = request();
        var item = incoming.getBenefits().get(0);
        item.setPublicationStatus(com.itplace.userapi.benefit.entity.enums.PublicationStatus.ARCHIVED);
        item.setHoldReason("reviewed historic duplicate");
        item.setPublicationReviewedAt(Instant.parse("2026-10-21T00:00:00Z"));
        item.setSourceEvidenceId(null);
        item.setSourceCapturedAt(null);
        Partner partner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        when(partnerRepository.findAllByPartnerNameIn(List.of("제휴사"))).thenReturn(List.of(partner));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(any())).thenReturn(List.of());

        BenefitSnapshotImportResponse response = service.importSnapshot(incoming, "internal-key");

        ArgumentCaptor<Iterable<BenefitCarrierPolicy>> policyCaptor = iterableCaptor();
        verify(benefitCarrierPolicyRepository).saveAll(policyCaptor.capture());
        BenefitCarrierPolicy imported = toList(policyCaptor.getValue()).get(0);
        assertThat(imported.getPublicationStatus())
                .isEqualTo(com.itplace.userapi.benefit.entity.enums.PublicationStatus.ARCHIVED);
        assertThat(imported.getActive()).isFalse();
        assertThat(imported.getHoldReason()).isEqualTo("reviewed historic duplicate");
        assertThat(imported.getPublicationReviewedAt()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
        assertThat(imported.getBenefit().getBenefitName()).isEqualTo("할인 혜택");
        assertThat(response.getTierBenefitCount()).isEqualTo(1);
        verify(carrierTierBenefitRepository).saveAll(argThat(tiers ->
                java.util.stream.StreamSupport.stream(tiers.spliterator(), false)
                        .anyMatch(tier -> "VIP 20% 할인".equals(tier.getContext()))));
    }

    @Test
    void legacySnapshotWithoutEvidenceCannotOverwriteExistingSourceMetadata() {
        Benefit existingBenefit = Benefit.builder()
                .benefitId(20L)
                .partner(Partner.builder().partnerId(7L).partnerName("제휴사").build())
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .benefitName("할인 혜택")
                .build();
        BenefitCarrierPolicy existingPolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(31L)
                .benefit(existingBenefit)
                .carrier(Carrier.SKT)
                .sourceKey("skt-benefit-1")
                .sourceEvidenceId(99L)
                .sourceCapturedAt(Instant.parse("2026-10-18T10:00:00Z"))
                .sourceLimitText("오래된 월 1회 한도")
                .conditions(new BenefitConditions(List.of("예전 상품"), List.of(),
                        List.of("오래된 월 1회 한도"), List.of(), List.of()))
                .build();
        when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(
                Carrier.SKT, List.of("skt-benefit-1"))).thenReturn(List.of(existingPolicy));

        BenefitSnapshotImportRequest incoming = request();
        incoming.getBenefits().get(0).setSourceEvidenceId(null);
        incoming.getBenefits().get(0).setSourceCapturedAt(null);
        assertThatThrownBy(() -> service.importSnapshot(incoming, "internal-key"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(existingPolicy.getSourceEvidenceId()).isEqualTo(99L);
        assertThat(existingPolicy.getSourceCapturedAt()).isEqualTo(Instant.parse("2026-10-18T10:00:00Z"));
        assertThat(existingPolicy.getSourceLimitText()).isEqualTo("오래된 월 1회 한도");
        assertThat(existingPolicy.getConditions().limits()).containsExactly("오래된 월 1회 한도");
    }

    private BenefitSnapshotImportRequest request() {
        BenefitSnapshotImportRequest request = new BenefitSnapshotImportRequest();
        request.setCarrier(Carrier.SKT);
        request.setCrawledAt(LocalDateTime.of(2026, 8, 14, 3, 0));

        BenefitSnapshotImportRequest.BenefitSnapshotItem item = new BenefitSnapshotImportRequest.BenefitSnapshotItem();
        item.setSourceKey("skt-benefit-1");
        item.setPartnerName("제휴사");
        item.setPartnerImage("https://images.itplace.click/img/spicus/logo.png");
        item.setPartnerCategory("푸드");
        item.setMainCategory(MainCategory.BASIC_BENEFIT);
        item.setBenefitName("할인 혜택");
        item.setType(BenefitType.DISCOUNT);
        item.setBenefitPolicyCode(BenefitPolicyCode.DAILY_ONCE);
        item.setUsageType(UsageType.ONLINE);
        item.setSourceUrl("https://source.example/skt-benefit-1");
        item.setSourceEvidenceId(901L);
        item.setSourceCapturedAt(Instant.parse("2026-08-13T00:00:00Z"));
        item.setUrl("https://benefit.example/skt-benefit-1");
        item.setActive(true);

        BenefitSnapshotImportRequest.TierBenefitSnapshot tier = new BenefitSnapshotImportRequest.TierBenefitSnapshot();
        tier.setGrade(Grade.SKT_VIP);
        tier.setContext("VIP 20% 할인");
        item.setTierBenefits(List.of(tier));

        request.setBenefits(List.of(item));
        return request;
    }

    private BenefitSnapshotImportRequest.TierBenefitSnapshot tier(Grade grade, String context, boolean isAll) {
        BenefitSnapshotImportRequest.TierBenefitSnapshot tier = new BenefitSnapshotImportRequest.TierBenefitSnapshot();
        tier.setGrade(grade);
        tier.setContext(context);
        tier.setIsAll(isAll);
        return tier;
    }
}
