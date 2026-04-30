package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
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
    private TierBenefitRepository tierBenefitRepository;

    private BenefitImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BenefitImportServiceImpl(benefitRepository, partnerRepository, tierBenefitRepository);
        ReflectionTestUtils.setField(service, "expectedApiKey", "internal-key");
    }

    @Test
    void importsSanitizedCarrierScopedSnapshotWithoutMembershipArtifacts() {
        BenefitSnapshotImportRequest request = request();
        Partner savedPartner = Partner.builder().partnerId(7L).partnerName("제휴사").build();
        Benefit savedBenefit = Benefit.builder().benefitId(11L).build();

        when(partnerRepository.findByPartnerName("제휴사")).thenReturn(Optional.empty());
        when(partnerRepository.save(any(Partner.class))).thenReturn(savedPartner);
        when(benefitRepository.findByCarrierAndSourceKey(Carrier.SKT, "skt-benefit-1")).thenReturn(Optional.empty());
        when(benefitRepository.save(any(Benefit.class))).thenReturn(savedBenefit);
        when(tierBenefitRepository.findAllByBenefit_BenefitId(11L)).thenReturn(List.of());

        BenefitSnapshotImportResponse response = service.importSnapshot(request, "internal-key");

        assertThat(response.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(response.getReceivedCount()).isEqualTo(1);
        assertThat(response.getTierBenefitCount()).isEqualTo(1);

        ArgumentCaptor<Benefit> benefitCaptor = ArgumentCaptor.forClass(Benefit.class);
        verify(benefitRepository).save(benefitCaptor.capture());
        Benefit imported = benefitCaptor.getValue();
        assertThat(imported.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(imported.getSourceKey()).isEqualTo("skt-benefit-1");
        assertThat(imported.getActive()).isTrue();
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
                    "tiers":[{"grade":"SKT_VIP","context":"VIP 20% 할인","isAll":false}]
                  }]
                }
                """;

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BenefitSnapshotImportRequest parsed = objectMapper.readValue(json, BenefitSnapshotImportRequest.class);

        assertThat(parsed.getCrawledAt()).isNotNull();
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
        item.setPartnerCategory("푸드");
        item.setMainCategory(MainCategory.BASIC_BENEFIT);
        item.setBenefitName("할인 혜택");
        item.setType(BenefitType.DISCOUNT);
        item.setUsageType(UsageType.ONLINE);
        item.setActive(true);

        BenefitSnapshotImportRequest.TierBenefitSnapshot tier = new BenefitSnapshotImportRequest.TierBenefitSnapshot();
        tier.setGrade(Grade.SKT_VIP);
        tier.setContext("VIP 20% 할인");
        item.setTierBenefits(List.of(tier));

        request.setBenefits(List.of(item));
        return request;
    }
}
