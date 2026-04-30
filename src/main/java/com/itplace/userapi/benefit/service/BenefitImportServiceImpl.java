package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BenefitImportServiceImpl implements BenefitImportService {

    private final BenefitRepository benefitRepository;
    private final PartnerRepository partnerRepository;
    private final TierBenefitRepository tierBenefitRepository;

    @Value("${app.internal.api-key:}")
    private String expectedApiKey;

    @Override
    @Transactional
    public BenefitSnapshotImportResponse importSnapshot(BenefitSnapshotImportRequest request, String apiKey) {
        validateInternalKey(apiKey);

        LocalDateTime crawledAt = request.getCrawledAt() != null ? request.getCrawledAt() : LocalDateTime.now();
        int tierBenefitCount = 0;

        for (BenefitSnapshotImportRequest.BenefitSnapshotItem item : request.getBenefits()) {
            Partner partner = upsertPartner(item);
            Benefit benefit = benefitRepository.findByCarrierAndSourceKey(request.getCarrier(), item.getSourceKey())
                    .orElseGet(Benefit::new);

            benefit.setPartner(partner);
            benefit.setMainCategory(item.getMainCategory());
            benefit.setBenefitName(item.getBenefitName());
            benefit.setType(item.getType());
            benefit.setDescription(item.getDescription());
            benefit.setManual(item.getManual());
            benefit.setUsageType(item.getUsageType());
            benefit.setUrl(item.getUrl());
            benefit.setCarrier(request.getCarrier());
            benefit.setActive(item.getActive() == null || item.getActive());
            benefit.setSourceKey(item.getSourceKey());
            benefit.setSourceCategory(item.getSourceCategory());
            benefit.setLastCrawledAt(crawledAt);

            Benefit savedBenefit = benefitRepository.save(benefit);
            tierBenefitCount += replaceTierBenefits(savedBenefit, item.getTierBenefits());
        }

        return BenefitSnapshotImportResponse.builder()
                .carrier(request.getCarrier())
                .receivedCount(request.getBenefits().size())
                .upsertedBenefitCount(request.getBenefits().size())
                .tierBenefitCount(tierBenefitCount)
                .build();
    }

    private void validateInternalKey(String apiKey) {
        if (!StringUtils.hasText(expectedApiKey) || !expectedApiKey.equals(apiKey)) {
            throw new BenefitImportUnauthorizedException(BenefitCode.BENEFIT_IMPORT_UNAUTHORIZED);
        }
    }

    private Partner upsertPartner(BenefitSnapshotImportRequest.BenefitSnapshotItem item) {
        Partner partner = partnerRepository.findByPartnerName(item.getPartnerName())
                .orElseGet(Partner::new);
        partner.setPartnerName(item.getPartnerName());
        partner.setImage(item.getPartnerImage());
        partner.setCategory(item.getPartnerCategory());
        return partnerRepository.save(partner);
    }

    private int replaceTierBenefits(Benefit benefit, List<BenefitSnapshotImportRequest.TierBenefitSnapshot> tierBenefits) {
        List<TierBenefit> existing = tierBenefitRepository.findAllByBenefit_BenefitId(benefit.getBenefitId());
        tierBenefitRepository.deleteAll(existing);

        if (tierBenefits == null || tierBenefits.isEmpty()) {
            return 0;
        }

        List<TierBenefit> replacements = tierBenefits.stream()
                .map(tier -> TierBenefit.builder()
                        .benefit(benefit)
                        .grade(tier.getGrade())
                        .context(tier.getContext())
                        .isAll(tier.getIsAll() != null && tier.getIsAll())
                        .discountValue(tier.getDiscountValue())
                        .build())
                .toList();
        tierBenefitRepository.saveAll(replacements);
        return replacements.size();
    }
}
