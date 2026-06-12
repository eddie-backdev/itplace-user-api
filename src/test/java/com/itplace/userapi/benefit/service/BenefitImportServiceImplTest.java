package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
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

    private BenefitImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BenefitImportServiceImpl(
                benefitRepository,
                partnerRepository,
                benefitCarrierPolicyRepository,
                carrierTierBenefitRepository
        );
        ReflectionTestUtils.setField(service, "expectedApiKey", "internal-key");
        lenient().when(partnerRepository.findAllByPartnerNameIn(any())).thenReturn(List.of());
        lenient().when(benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(any(Carrier.class), any()))
                .thenReturn(List.of());
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
    void importsSanitizedCarrierScopedSnapshotWithoutMembershipArtifacts() {
        BenefitSnapshotImportRequest request = request();

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getReceivedCount()).isEqualTo(1);
        assertThat(response.getTierBenefitCount()).isEqualTo(1);

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
        assertThat(policy.getActive()).isTrue();

        ArgumentCaptor<Iterable<CarrierTierBenefit>> carrierTierCaptor = iterableCaptor();
        verify(carrierTierBenefitRepository).saveAll(carrierTierCaptor.capture());
        List<CarrierTierBenefit> carrierTiers = toList(carrierTierCaptor.getValue());
        assertThat(carrierTiers).hasSize(1);
        assertThat(carrierTiers.get(0).getBenefitCarrierPolicy()).isSameAs(policy);
        assertThat(carrierTiers.get(0).getGrade()).isEqualTo(Grade.SKT_VIP);

        verify(partnerRepository, never()).findByPartnerName(any(String.class));
        verify(benefitRepository, never()).findByPartner_PartnerIdAndCanonicalKey(any(Long.class), any(String.class));
        verify(benefitCarrierPolicyRepository, never()).findByCarrierAndSourceKey(any(Carrier.class), any(String.class));
        verify(carrierTierBenefitRepository, never()).findAllByBenefitCarrierPolicy(any(BenefitCarrierPolicy.class));
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

        verify(carrierTierBenefitRepository).deleteAll(List.of(existingTier));
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
        assertThat(parsed.getBenefits().get(0).getTierBenefits()).hasSize(1);
        assertThat(parsed.getBenefits().get(0).getTierBenefits().get(0).getGrade()).isEqualTo(Grade.SKT_VIP);
    }

    private BenefitSnapshotImportRequest request() {
        BenefitSnapshotImportRequest request = new BenefitSnapshotImportRequest();
        request.setCarrier(Carrier.SKT);

        BenefitSnapshotImportRequest.BenefitSnapshotItem item = new BenefitSnapshotImportRequest.BenefitSnapshotItem();
        item.setSourceKey("skt-benefit-1");
        item.setPartnerName("제휴사");
        item.setPartnerImage("https://images.itplace.click/img/spicus/logo.png");
        item.setPartnerCategory("푸드");
        item.setMainCategory(MainCategory.BASIC_BENEFIT);
        item.setBenefitName("할인 혜택");
        item.setType(BenefitType.DISCOUNT);
        item.setUsageType(UsageType.ONLINE);
        item.setSourceUrl("https://source.example/skt-benefit-1");
        item.setUrl("https://benefit.example/skt-benefit-1");
        item.setActive(true);

        BenefitSnapshotImportRequest.TierBenefitSnapshot tier = new BenefitSnapshotImportRequest.TierBenefitSnapshot();
        tier.setGrade(Grade.SKT_VIP);
        tier.setContext("VIP 20% 할인");
        item.setTierBenefits(List.of(tier));

        request.setBenefits(List.of(item));
        return request;
    }
}
