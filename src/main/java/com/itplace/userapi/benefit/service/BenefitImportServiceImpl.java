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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items = request.getBenefits();
        Map<String, Partner> partnerByName = upsertPartners(items);
        Map<String, BenefitCarrierPolicy> policyBySourceKey = findPoliciesBySourceKey(request.getCarrier(), items);
        Map<BenefitKey, Benefit> benefitByKey = findBenefitsByKey(items, partnerByName);

        List<ImportRow> rows = new ArrayList<>(items.size());
        List<Benefit> benefitsToSave = new ArrayList<>(items.size());
        int tierBenefitCount = 0;

        for (BenefitSnapshotImportRequest.BenefitSnapshotItem item : items) {
            Partner partner = partnerByName.get(item.getPartnerName());
            String canonicalKey = resolveCanonicalKey(partner, item);
            BenefitCarrierPolicy policy = policyBySourceKey.get(item.getSourceKey());
            Benefit benefit = policy != null
                    ? policy.getBenefit()
                    : benefitByKey.get(new BenefitKey(partner.getPartnerId(), canonicalKey));
            if (benefit == null) {
                benefit = new Benefit();
            }

            benefit.setPartner(partner);
            benefit.setMainCategory(item.getMainCategory());
            benefit.setBenefitName(item.getBenefitName());
            benefit.setCanonicalKey(canonicalKey);
            benefit.setActive(item.getActive() == null || item.getActive());

            rows.add(new ImportRow(item, benefit, policy));
            benefitsToSave.add(benefit);
            tierBenefitCount += item.getTierBenefits() == null ? 0 : item.getTierBenefits().size();
        }

        List<Benefit> savedBenefits = benefitRepository.saveAll(benefitsToSave);
        List<BenefitCarrierPolicy> policiesToSave = new ArrayList<>(rows.size());
        Map<String, ImportRow> latestRowBySourceKey = new LinkedHashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            Benefit savedBenefit = savedBenefits.get(i);
            BenefitCarrierPolicy policy = prepareCarrierPolicy(
                    row.policy(),
                    savedBenefit,
                    request.getCarrier(),
                    row.item(),
                    crawledAt
            );
            policiesToSave.add(policy);
            latestRowBySourceKey.put(row.item().getSourceKey(), new ImportRow(row.item(), savedBenefit, policy));
        }

        List<BenefitCarrierPolicy> savedPolicies = benefitCarrierPolicyRepository.saveAll(policiesToSave);
        replaceCarrierTierBenefits(savedPolicies, latestRowBySourceKey);

        return BenefitSnapshotImportResponse.builder()
                .carrier(request.getCarrier())
                .receivedCount(items.size())
                .upsertedBenefitCount(items.size())
                .tierBenefitCount(tierBenefitCount)
                .build();
    }

    private void validateInternalKey(String apiKey) {
        if (!StringUtils.hasText(expectedApiKey) || !StringUtils.hasText(apiKey) || !constantTimeEquals(expectedApiKey, apiKey)) {
            throw new BenefitImportUnauthorizedException(BenefitCode.BENEFIT_IMPORT_UNAUTHORIZED);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private Map<String, Partner> upsertPartners(List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items) {
        List<String> partnerNames = items.stream()
                .map(BenefitSnapshotImportRequest.BenefitSnapshotItem::getPartnerName)
                .distinct()
                .toList();
        Map<String, Partner> partnerByName = partnerRepository.findAllByPartnerNameIn(partnerNames).stream()
                .collect(Collectors.toMap(Partner::getPartnerName, Function.identity(), (first, ignored) -> first));

        Map<String, Partner> upsertTargets = new LinkedHashMap<>();
        for (BenefitSnapshotImportRequest.BenefitSnapshotItem item : items) {
            Partner partner = partnerByName.computeIfAbsent(item.getPartnerName(), ignored -> new Partner());
            partner.setPartnerName(item.getPartnerName());
            partner.setImage(PartnerImagePolicy.resolveCanonicalImage(
                    partner.getImage(),
                    item.getPartnerImage()
            ));
            partner.setCategory(item.getPartnerCategory());
            upsertTargets.put(item.getPartnerName(), partner);
        }

        return partnerRepository.saveAll(new ArrayList<>(upsertTargets.values())).stream()
                .collect(Collectors.toMap(Partner::getPartnerName, Function.identity(), (first, ignored) -> first));
    }

    private Map<String, BenefitCarrierPolicy> findPoliciesBySourceKey(
            Carrier carrier,
            List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items
    ) {
        List<String> sourceKeys = items.stream()
                .map(BenefitSnapshotImportRequest.BenefitSnapshotItem::getSourceKey)
                .distinct()
                .toList();

        return benefitCarrierPolicyRepository.findAllByCarrierAndSourceKeyInWithBenefit(carrier, sourceKeys).stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getSourceKey, Function.identity(), (first, ignored) -> first));
    }

    private Map<BenefitKey, Benefit> findBenefitsByKey(
            List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items,
            Map<String, Partner> partnerByName
    ) {
        List<Long> partnerIds = partnerByName.values().stream()
                .map(Partner::getPartnerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> canonicalKeys = items.stream()
                .map(item -> resolveCanonicalKey(partnerByName.get(item.getPartnerName()), item))
                .distinct()
                .toList();
        if (partnerIds.isEmpty() || canonicalKeys.isEmpty()) {
            return Map.of();
        }

        return benefitRepository.findAllByPartnerIdsAndCanonicalKeys(partnerIds, canonicalKeys).stream()
                .collect(Collectors.toMap(
                        benefit -> new BenefitKey(benefit.getPartner().getPartnerId(), benefit.getCanonicalKey()),
                        Function.identity(),
                        (first, ignored) -> first
                ));
    }

    private BenefitCarrierPolicy prepareCarrierPolicy(
            BenefitCarrierPolicy policy,
            Benefit benefit,
            Carrier carrier,
            BenefitSnapshotImportRequest.BenefitSnapshotItem item,
            LocalDateTime crawledAt
    ) {
        BenefitCarrierPolicy target = policy == null
                ? BenefitCarrierPolicy.builder()
                .benefit(benefit)
                .carrier(carrier)
                .sourceKey(item.getSourceKey())
                .build()
                : policy;

        target.setBenefit(benefit);
        target.setCarrier(carrier);
        target.setActive(item.getActive() == null || item.getActive());
        target.setSourceKey(item.getSourceKey());
        target.setSourceUrl(item.getSourceUrl());
        target.setSourceCategory(item.getSourceCategory());
        target.setLastCrawledAt(crawledAt);
        target.setCarrierBenefitName(item.getBenefitName());
        target.setType(item.getType());
        target.setDescription(item.getDescription());
        target.setManual(item.getManual());
        target.setUsageType(item.getUsageType());
        target.setUrl(resolveBenefitUrl(item));

        return target;
    }

    private void replaceCarrierTierBenefits(
            List<BenefitCarrierPolicy> savedPolicies,
            Map<String, ImportRow> latestRowBySourceKey
    ) {
        if (savedPolicies.isEmpty()) {
            return;
        }

        List<CarrierTierBenefit> existingTiers = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(savedPolicies);
        if (!existingTiers.isEmpty()) {
            carrierTierBenefitRepository.deleteAll(existingTiers);
        }

        List<CarrierTierBenefit> replacements = new ArrayList<>();
        Map<String, BenefitCarrierPolicy> savedPolicyBySourceKey = savedPolicies.stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getSourceKey, Function.identity(), (first, second) -> second));
        latestRowBySourceKey.forEach((sourceKey, row) -> {
            BenefitCarrierPolicy savedPolicy = savedPolicyBySourceKey.get(sourceKey);
            if (savedPolicy != null) {
                replacements.addAll(createCarrierTierBenefits(savedPolicy, row.item().getTierBenefits()));
            }
        });
        if (!replacements.isEmpty()) {
            carrierTierBenefitRepository.saveAll(replacements);
        }
    }

    private List<CarrierTierBenefit> createCarrierTierBenefits(
            BenefitCarrierPolicy policy,
            List<BenefitSnapshotImportRequest.TierBenefitSnapshot> tierBenefits
    ) {
        if (tierBenefits == null || tierBenefits.isEmpty()) {
            return List.of();
        }

        return tierBenefits.stream()
                .map(tier -> CarrierTierBenefit.builder()
                        .benefitCarrierPolicy(policy)
                        .grade(tier.getGrade())
                        .context(tier.getContext())
                        .isAll(tier.getIsAll() != null && tier.getIsAll())
                        .discountValue(tier.getDiscountValue())
                        .build())
                .toList();
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

    private record ImportRow(
            BenefitSnapshotImportRequest.BenefitSnapshotItem item,
            Benefit benefit,
            BenefitCarrierPolicy policy
    ) {
    }

    private record BenefitKey(Long partnerId, String canonicalKey) {
    }
}
