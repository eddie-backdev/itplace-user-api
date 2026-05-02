package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.partner.service.PartnerImagePolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
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
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;

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
            String canonicalKey = resolveCanonicalKey(partner, item);
            Benefit benefit = benefitCarrierPolicyRepository.findByCarrierAndSourceKey(request.getCarrier(), item.getSourceKey())
                    .map(BenefitCarrierPolicy::getBenefit)
                    .or(() -> benefitRepository.findByPartner_PartnerIdAndCanonicalKey(partner.getPartnerId(), canonicalKey))
                    .orElseGet(Benefit::new);

            benefit.setPartner(partner);
            benefit.setMainCategory(item.getMainCategory());
            benefit.setBenefitName(item.getBenefitName());
            benefit.setCanonicalKey(canonicalKey);
            benefit.setActive(item.getActive() == null || item.getActive());

            Benefit savedBenefit = benefitRepository.save(benefit);
            replaceCarrierPolicy(savedBenefit, request.getCarrier(), item, crawledAt);
            tierBenefitCount += item.getTierBenefits() == null ? 0 : item.getTierBenefits().size();
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
        partner.setImage(PartnerImagePolicy.resolveCanonicalImage(
                partner.getImage(),
                item.getPartnerImage()
        ));
        partner.setCategory(item.getPartnerCategory());
        return partnerRepository.save(partner);
    }

    private void replaceCarrierPolicy(
            Benefit benefit,
            Carrier carrier,
            BenefitSnapshotImportRequest.BenefitSnapshotItem item,
            LocalDateTime crawledAt
    ) {
        BenefitCarrierPolicy policy = benefitCarrierPolicyRepository.findByCarrierAndSourceKey(carrier, item.getSourceKey())
                .orElseGet(() -> BenefitCarrierPolicy.builder()
                        .benefit(benefit)
                        .carrier(carrier)
                        .sourceKey(item.getSourceKey())
                        .build());

        policy.setBenefit(benefit);
        policy.setCarrier(carrier);
        policy.setActive(item.getActive() == null || item.getActive());
        policy.setSourceKey(item.getSourceKey());
        policy.setSourceUrl(item.getSourceUrl());
        policy.setSourceCategory(item.getSourceCategory());
        policy.setLastCrawledAt(crawledAt);
        policy.setCarrierBenefitName(item.getBenefitName());
        policy.setType(item.getType());
        policy.setDescription(item.getDescription());
        policy.setManual(item.getManual());
        policy.setUsageType(item.getUsageType());
        policy.setUrl(resolveBenefitUrl(item));

        BenefitCarrierPolicy savedPolicy = benefitCarrierPolicyRepository.save(policy);
        carrierTierBenefitRepository.deleteAll(
                carrierTierBenefitRepository.findAllByBenefitCarrierPolicy(savedPolicy)
        );
        saveCarrierTierBenefits(savedPolicy, item.getTierBenefits());
    }

    private void saveCarrierTierBenefits(
            BenefitCarrierPolicy policy,
            List<BenefitSnapshotImportRequest.TierBenefitSnapshot> tierBenefits
    ) {
        if (tierBenefits == null || tierBenefits.isEmpty()) {
            return;
        }

        List<CarrierTierBenefit> replacements = tierBenefits.stream()
                .map(tier -> CarrierTierBenefit.builder()
                        .benefitCarrierPolicy(policy)
                        .grade(tier.getGrade())
                        .context(tier.getContext())
                        .isAll(tier.getIsAll() != null && tier.getIsAll())
                        .discountValue(tier.getDiscountValue())
                        .build())
                .toList();
        carrierTierBenefitRepository.saveAll(replacements);
    }

    private String resolveCanonicalKey(Partner partner, BenefitSnapshotImportRequest.BenefitSnapshotItem item) {
        String partnerKey = partner.getPartnerId() == null ? item.getPartnerName() : partner.getPartnerId().toString();
        return normalize(partnerKey) + ":" + normalize(item.getBenefitName());
    }

    private String resolveBenefitUrl(BenefitSnapshotImportRequest.BenefitSnapshotItem item) {
        return StringUtils.hasText(item.getUrl()) ? item.getUrl() : item.getSourceUrl();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]+", "");
    }
}
