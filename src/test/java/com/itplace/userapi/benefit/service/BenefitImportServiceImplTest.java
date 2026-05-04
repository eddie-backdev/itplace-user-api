package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import java.util.Optional;
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
        lenient().when(benefitRepository.findByPartner_PartnerIdAndCanonicalKey(any(Long.class), any(String.class)))
                .thenReturn(Optional.empty());
        lenient().when(benefitCarrierPolicyRepository.findByCarrierAndSourceKey(any(Carrier.class), any(String.class)))
                .thenReturn(Optional.empty());
        lenient().when(benefitCarrierPolicyRepository.save(any(BenefitCarrierPolicy.class))).thenAnswer(invocation -> {
            BenefitCarrierPolicy policy = invocation.getArgument(0);
            if (policy.getBenefitCarrierPolicyId() == null) {
                policy.setBenefitCarrierPolicyId(31L);
            }
            return policy;
        });
        lenient().when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicy(any(BenefitCarrierPolicy.class)))
                .thenReturn(List.of());
    }

    @Test
    void importsSanitizedCarrierScopedSnapshotWithoutMembershipArtifacts() {
        BenefitSnapshotImportRequest request = request();
        Partner savedPartner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit savedBenefit = Benefit.builder().benefitId(11L).build();

        when(partnerRepository.findByPartnerName("제휴사")).thenReturn(Optional.empty());
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> {
            Partner partner = invocation.getArgument(0);
            partner.setPartnerId(7L);
            return partner;
        });
        when(benefitRepository.save(any(Benefit.class))).thenReturn(savedBenefit);

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getReceivedCount()).isEqualTo(1);
        assertThat(response.getTierBenefitCount()).isEqualTo(1);

        ArgumentCaptor<Benefit> benefitCaptor = ArgumentCaptor.forClass(Benefit.class);
        verify(benefitRepository).save(benefitCaptor.capture());
        Benefit imported = benefitCaptor.getValue();
        assertThat(imported.getCanonicalKey()).isEqualTo("7:할인혜택");
        assertThat(imported.getActive()).isTrue();

        ArgumentCaptor<Partner> partnerCaptor = ArgumentCaptor.forClass(Partner.class);
        verify(partnerRepository).save(partnerCaptor.capture());
        Partner importedPartner = partnerCaptor.getValue();
        assertThat(importedPartner.getCategory()).isEqualTo("푸드");
        assertThat(importedPartner.getImage()).isEqualTo("https://images.itplace.click/img/spicus/logo.png");

        ArgumentCaptor<BenefitCarrierPolicy> policyCaptor = ArgumentCaptor.forClass(BenefitCarrierPolicy.class);
        verify(benefitCarrierPolicyRepository).save(policyCaptor.capture());
        BenefitCarrierPolicy policy = policyCaptor.getValue();
        assertThat(policy.getBenefit()).isSameAs(savedBenefit);
        assertThat(policy.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(policy.getSourceKey()).isEqualTo("skt-benefit-1");
        assertThat(policy.getSourceUrl()).isEqualTo("https://source.example/skt-benefit-1");
        assertThat(policy.getUrl()).isEqualTo("https://benefit.example/skt-benefit-1");
        assertThat(policy.getCarrierBenefitName()).isEqualTo("할인 혜택");
        assertThat(policy.getActive()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarrierTierBenefit>> carrierTierCaptor = ArgumentCaptor.forClass(List.class);
        verify(carrierTierBenefitRepository).saveAll(carrierTierCaptor.capture());
        assertThat(carrierTierCaptor.getValue()).hasSize(1);
        assertThat(carrierTierCaptor.getValue().get(0).getBenefitCarrierPolicy()).isSameAs(policy);
        assertThat(carrierTierCaptor.getValue().get(0).getGrade()).isEqualTo(Grade.SKT_VIP);
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
