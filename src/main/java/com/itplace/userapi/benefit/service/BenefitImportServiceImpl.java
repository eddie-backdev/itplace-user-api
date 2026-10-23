package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportStatus;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.BenefitSnapshotImportState;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import com.itplace.userapi.benefit.entity.enums.PublicationStatus;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.exception.BenefitImportUnauthorizedException;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.BenefitSnapshotImportStateRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.map.service.PartnerBenefitCacheService;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.partner.service.PartnerImagePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BenefitImportServiceImpl implements BenefitImportService {

    private final BenefitRepository benefitRepository;
    private final PartnerRepository partnerRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final BenefitPolicyRepository benefitPolicyRepository;
    private final BenefitSnapshotImportStateRepository benefitSnapshotImportStateRepository;
    private final PartnerBenefitCacheService partnerBenefitCacheService;
    private final BenefitCacheRefreshService benefitCacheRefreshService;
    private final ReferenceDataService referenceDataService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.internal.api-key:}")
    private String expectedApiKey;

    @Override
    @Transactional
    public BenefitSnapshotImportResponse importSnapshot(BenefitSnapshotImportRequest request, String apiKey) {
        validateInternalKey(apiKey);
        validateReferenceCodes(request);

        LocalDateTime crawledAt = Objects.requireNonNull(
                request.getCrawledAt(),
                "스냅샷 생성 시각은 필수 항목입니다."
        ).truncatedTo(ChronoUnit.MICROS);
        BenefitSnapshotImportState importState = benefitSnapshotImportStateRepository
                .findByCarrierForUpdate(request.getCarrier())
                .orElseThrow(() -> new IllegalStateException(
                        "혜택 스냅샷 수신 상태가 초기화되지 않았습니다: " + request.getCarrier()
                ));
        LocalDateTime latestKnownCrawledAt = latestKnownCrawledAt(importState, request.getCarrier());
        BenefitSnapshotImportStatus ignoredStatus = ignoredStatus(latestKnownCrawledAt, crawledAt);
        if (ignoredStatus != null) {
            importState.markApplied(latestKnownCrawledAt);
            // DB commit 이후 Redis만 실패한 import도 동일 스냅샷 재시도로 복구한다.
            if (ignoredStatus == BenefitSnapshotImportStatus.DUPLICATE) {
                // 제휴사 이동으로 현재 carrier에서 사라진 이전 제휴사까지 복구한다.
                partnerBenefitCacheService.invalidateAfterCommit(partnerRepository.findAll().stream()
                        .map(Partner::getPartnerId).toList());
                benefitCacheRefreshService.refreshAfterCommit();
            }
            return ignoredResponse(request, latestKnownCrawledAt, ignoredStatus);
        }

        List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items = request.getBenefits();
        Map<String, Partner> partnerByName = upsertPartners(items);
        Map<String, BenefitCarrierPolicy> policyBySourceKey = findPoliciesBySourceKey(request.getCarrier(), items);
        Set<String> preserveHeldSourceKeys = new LinkedHashSet<>();
        Set<String> preserveArchivedSourceKeys = new LinkedHashSet<>();
        for (BenefitSnapshotImportRequest.BenefitSnapshotItem item : items) {
            BenefitCarrierPolicy existingPolicy = policyBySourceKey.get(item.getSourceKey());
            boolean archived = item.getPublicationStatus() == PublicationStatus.ARCHIVED
                    || (existingPolicy != null
                    && existingPolicy.getPublicationStatus() == PublicationStatus.ARCHIVED);
            boolean freezeArchivedHistory = existingPolicy != null && archived;
            if (freezeArchivedHistory) preserveArchivedSourceKeys.add(item.getSourceKey());
            if (policyBySourceKey.containsKey(item.getSourceKey())
                    && !hasValidSourceEvidence(item, request.getCarrier())) {
                if (archived) {
                    preserveArchivedSourceKeys.add(item.getSourceKey());
                } else if (item.getPublicationStatus() == PublicationStatus.HELD) {
                    preserveHeldSourceKeys.add(item.getSourceKey());
                } else {
                    throw new IllegalArgumentException("Existing benefit row rejected because incoming source evidence is missing or mismatched: "
                            + item.getSourceKey());
                }
            }
        }
        Map<BenefitKey, Benefit> benefitByKey = findBenefitsByKey(items, partnerByName);

        List<ImportRow> rows = new ArrayList<>(items.size());
        List<Benefit> benefitsToSave = new ArrayList<>(items.size());
        int tierBenefitCount = 0;
        Set<Long> changedPartnerIds = new LinkedHashSet<>();

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

            if (benefit.getPartner() != null) changedPartnerIds.add(benefit.getPartner().getPartnerId());
            changedPartnerIds.add(partner.getPartnerId());
            boolean preserveArchived = preserveArchivedSourceKeys.contains(item.getSourceKey());
            boolean preserveHeld = !preserveArchived && preserveHeldSourceKeys.contains(item.getSourceKey());
            if (!preserveHeld && !preserveArchived) {
                benefit.setPartner(partner);
                benefit.setMainCategory(item.getMainCategory());
                benefit.setBenefitName(item.getBenefitName());
                benefit.setCanonicalKey(canonicalKey);
                benefit.setActive(item.getActive() == null || item.getActive());
            }

            rows.add(new ImportRow(item, benefit, policy, preserveHeld, preserveArchived));
            benefitsToSave.add(benefit);
            if (!preserveHeld && !preserveArchived) {
                tierBenefitCount += item.getTierBenefits() == null ? 0 : item.getTierBenefits().size();
            }
        }

        List<Benefit> savedBenefits = benefitRepository.saveAll(benefitsToSave);
        List<BenefitCarrierPolicy> policiesToSave = new ArrayList<>(rows.size());
        Map<String, ImportRow> latestRowBySourceKey = new LinkedHashMap<>();

        Map<BenefitPolicyCode, BenefitPolicy> benefitPolicies = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            Benefit savedBenefit = savedBenefits.get(i);
            BenefitCarrierPolicy policy = prepareCarrierPolicy(
                    row.policy(),
                    savedBenefit,
                    request.getCarrier(),
                    row.item(),
                    crawledAt,
                    row.preserveHeld(),
                    row.preserveArchived(),
                    benefitPolicies
            );
            policiesToSave.add(policy);
            if (!row.preserveHeld() && !row.preserveArchived()) {
                latestRowBySourceKey.put(row.item().getSourceKey(),
                        new ImportRow(row.item(), savedBenefit, policy, false, false));
            }
        }

        List<BenefitCarrierPolicy> savedPolicies = benefitCarrierPolicyRepository.saveAll(policiesToSave);
        replaceCarrierTierBenefits(savedPolicies, latestRowBySourceKey);
        Set<MainCategory> incomingCategories = items.stream()
                .map(BenefitSnapshotImportRequest.BenefitSnapshotItem::getMainCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        changedPartnerIds.addAll(inactivateMissingPolicies(
                request.getCarrier(), latestRowBySourceKey.keySet(), incomingCategories, crawledAt));
        importState.markApplied(crawledAt);
        partnerBenefitCacheService.invalidateAfterCommit(changedPartnerIds.stream().filter(Objects::nonNull).toList());
        benefitCacheRefreshService.refreshAfterCommit();

        return BenefitSnapshotImportResponse.builder()
                .carrier(request.getCarrier())
                .receivedCount(items.size())
                .upsertedBenefitCount(items.size())
                .tierBenefitCount(tierBenefitCount)
                .status(BenefitSnapshotImportStatus.APPLIED)
                .lastAppliedCrawledAt(crawledAt)
                .build();
    }

    private LocalDateTime latestKnownCrawledAt(
            BenefitSnapshotImportState importState,
            Carrier carrier
    ) {
        LocalDateTime stateCrawledAt = importState.getLastAppliedCrawledAt();
        LocalDateTime policyCrawledAt = benefitCarrierPolicyRepository
                .findLatestCrawledAtByCarrier(carrier)
                .orElse(null);
        if (stateCrawledAt == null) {
            return policyCrawledAt;
        }
        if (policyCrawledAt == null) {
            return stateCrawledAt;
        }
        return stateCrawledAt.isAfter(policyCrawledAt) ? stateCrawledAt : policyCrawledAt;
    }

    private BenefitSnapshotImportStatus ignoredStatus(
            LocalDateTime lastApplied,
            LocalDateTime crawledAt
    ) {
        if (lastApplied == null) {
            return null;
        }
        if (crawledAt.isEqual(lastApplied)) {
            return BenefitSnapshotImportStatus.DUPLICATE;
        }
        if (crawledAt.isBefore(lastApplied)) {
            return BenefitSnapshotImportStatus.STALE;
        }
        return null;
    }

    private BenefitSnapshotImportResponse ignoredResponse(
            BenefitSnapshotImportRequest request,
            LocalDateTime lastAppliedCrawledAt,
            BenefitSnapshotImportStatus status
    ) {
        return BenefitSnapshotImportResponse.builder()
                .carrier(request.getCarrier())
                .receivedCount(request.getBenefits().size())
                .upsertedBenefitCount(0)
                .tierBenefitCount(0)
                .status(status)
                .lastAppliedCrawledAt(lastAppliedCrawledAt)
                .build();
    }

    private void validateInternalKey(String apiKey) {
        if (!StringUtils.hasText(expectedApiKey) || !StringUtils.hasText(apiKey) || !constantTimeEquals(expectedApiKey, apiKey)) {
            throw new BenefitImportUnauthorizedException(BenefitCode.BENEFIT_IMPORT_UNAUTHORIZED);
        }
    }

    private void validateReferenceCodes(BenefitSnapshotImportRequest request) {
        if (request == null || request.getBenefits() == null) return;
        for (var item : request.getBenefits()) {
            if (item.getMainCategory() == null || !referenceDataService.isActiveProgram(item.getMainCategory().getCode())) {
                throw new com.itplace.userapi.benefit.exception.InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
            }
            if (StringUtils.hasText(item.getPartnerCategory()) && !referenceDataService.isActiveCategory(item.getPartnerCategory().trim())) {
                throw new com.itplace.userapi.benefit.exception.InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND);
            }
            for (var tier : item.getTierBenefits()) {
                if (tier.getGrade() == null || !referenceDataService.isBenefitGrade(request.getCarrier(), tier.getGrade())) {
                    throw new com.itplace.userapi.benefit.exception.InvalidEnumException(BenefitCode.INVALID_GRADE_FOR_BENEFIT);
                }
            }
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private Map<String, Partner> upsertPartners(List<BenefitSnapshotImportRequest.BenefitSnapshotItem> items) {
        List<String> partnerNames = items.stream()
                .flatMap(item -> partnerLookupNames(item).stream())
                .distinct()
                .toList();
        Map<String, Partner> existingPartnerByName = partnerRepository.findAllByPartnerNameIn(partnerNames).stream()
                .collect(Collectors.toMap(Partner::getPartnerName, Function.identity(), (first, ignored) -> first));

        Map<String, Partner> upsertTargets = new LinkedHashMap<>();
        for (BenefitSnapshotImportRequest.BenefitSnapshotItem item : items) {
            Partner partner = upsertTargets.get(item.getPartnerName());
            if (partner == null) {
                partner = existingPartnerByName.get(item.getPartnerName());
            }
            if (partner == null) {
                partner = partnerLookupNames(item).stream()
                        .map(existingPartnerByName::get)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseGet(Partner::new);
            }
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

    private List<String> partnerLookupNames(BenefitSnapshotImportRequest.BenefitSnapshotItem item) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (StringUtils.hasText(item.getPartnerName())) {
            names.add(item.getPartnerName());
        }
        if (item.getPartnerAliases() != null) {
            item.getPartnerAliases().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(names::add);
        }
        return List.copyOf(names);
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
            LocalDateTime crawledAt,
            boolean preserveHeld,
            boolean preserveArchived,
            Map<BenefitPolicyCode, BenefitPolicy> benefitPolicies
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
        if (preserveHeld) {
            target.setPublicationStatus(PublicationStatus.HELD);
            target.setHoldReason(StringUtils.hasText(item.getHoldReason()) ? item.getHoldReason()
                    : StringUtils.hasText(target.getHoldReason()) ? target.getHoldReason()
                    : "Source evidence is missing or mismatched");
            target.setActive(false);
            return target;
        }
        boolean previouslyArchived = target.getPublicationStatus() == PublicationStatus.ARCHIVED;
        boolean archived = preserveArchived || previouslyArchived
                || item.getPublicationStatus() == PublicationStatus.ARCHIVED;
        if (archived) {
            target.setPublicationStatus(PublicationStatus.ARCHIVED);
            target.setHoldReason(previouslyArchived && StringUtils.hasText(target.getHoldReason())
                    ? target.getHoldReason()
                    : StringUtils.hasText(item.getHoldReason()) ? item.getHoldReason()
                    : "Archived publication");
            target.setPublicationReviewedAt(previouslyArchived && target.getPublicationReviewedAt() != null
                    ? target.getPublicationReviewedAt()
                    : item.getPublicationReviewedAt());
            target.setActive(false);
        }
        if (preserveArchived || previouslyArchived) return target;
        boolean held = !archived && (item.getPublicationStatus() == PublicationStatus.HELD
                || target.getPublicationStatus() == PublicationStatus.HELD
                || (policy == null && !hasValidSourceEvidence(item, carrier)));
        if (!archived) {
            target.setPublicationStatus(held ? PublicationStatus.HELD : PublicationStatus.APPROVED);
            target.setHoldReason(held
                    ? (StringUtils.hasText(item.getHoldReason()) ? item.getHoldReason()
                        : StringUtils.hasText(target.getHoldReason()) ? target.getHoldReason()
                        : "Source evidence is missing or publication is held")
                    : null);
            target.setPublicationReviewedAt(item.getPublicationReviewedAt() != null
                    ? item.getPublicationReviewedAt() : target.getPublicationReviewedAt());
            target.setActive(!held && (item.getActive() == null || item.getActive()));
        }
        target.setSourceKey(item.getSourceKey());
        target.setSourceUrl(item.getSourceUrl());
        target.setSourceCategory(item.getSourceCategory());
        target.setLastCrawledAt(crawledAt);
        if (hasValidSourceEvidence(item, carrier)) {
            target.setSourceEvidenceId(item.getSourceEvidenceId());
            target.setSourceCapturedAt(item.getSourceCapturedAt());
        }
        target.setSourceLimitText(item.getSourceLimitText());
        target.setConditions(item.getConditions());
        target.setCarrierBenefitName(item.getBenefitName());
        MembershipOptionType incomingOption = item.getMembershipOptionType();
        String incomingOptionLabel = item.getSourceOptionLabel();
        boolean hasIncomingOptionMetadata = incomingOption != null || incomingOptionLabel != null;
        boolean inferredLegacyOption = false;
        if (incomingOption == null && incomingOptionLabel == null && (target.getMembershipOptionType() == null
                || target.getMembershipOptionType() == MembershipOptionType.UNKNOWN)) {
            MembershipOptionType legacyOption = legacySktOption(carrier, item.getBenefitName());
            if (legacyOption != null) {
                incomingOption = legacyOption;
                incomingOptionLabel = legacyOption.getDisplayLabel();
                inferredLegacyOption = true;
            }
        }
        if (incomingOption != null) target.setMembershipOptionType(incomingOption);
        else if (target.getMembershipOptionType() == null) target.setMembershipOptionType(MembershipOptionType.UNKNOWN);
        if (hasIncomingOptionMetadata || inferredLegacyOption) target.setSourceOptionLabel(incomingOptionLabel);
        target.setType(item.getType());
        target.setDescription(item.getDescription());
        target.setManual(item.getManual());
        BenefitPolicyCode code = item.getBenefitPolicyCode() == null ? BenefitPolicyCode.UNKNOWN : item.getBenefitPolicyCode();
        target.setBenefitPolicy(benefitPolicies.computeIfAbsent(code, this::resolveBenefitPolicy));
        target.setUsageType(item.getUsageType());
        target.setUrl(resolveBenefitUrl(item));

        return target;
    }

    private boolean hasValidSourceEvidence(BenefitSnapshotImportRequest.BenefitSnapshotItem item, Carrier carrier) {
        if (item.getSourceEvidenceId() == null || item.getSourceEvidenceId() <= 0 || item.getSourceCapturedAt() == null
                || !StringUtils.hasText(item.getSourceUrl())) return false;
        try {
            java.net.URI uri = java.net.URI.create(item.getSourceUrl().trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())) return false;
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM rawBenefitSource
                    WHERE id = ? AND carrier = ? AND sourceUrl = ?
                      AND capturedAt <= ? AND lastSeenAt >= ?
                    """, Integer.class, item.getSourceEvidenceId(), carrier.name(), item.getSourceUrl().trim(),
                    java.sql.Timestamp.from(item.getSourceCapturedAt()), java.sql.Timestamp.from(item.getSourceCapturedAt()));
            return count != null && count > 0;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private MembershipOptionType legacySktOption(Carrier carrier, String benefitName) {
        if (carrier != Carrier.SKT || benefitName == null) return null;
        if (benefitName.endsWith(" 할인형")) return MembershipOptionType.DISCOUNT;
        if (benefitName.endsWith(" 적립형")) return MembershipOptionType.POINT_ACCRUAL;
        return null;
    }

    private BenefitPolicy resolveBenefitPolicy(BenefitPolicyCode requestedCode) {
        BenefitPolicyCode code = requestedCode == null ? BenefitPolicyCode.UNKNOWN : requestedCode;
        return benefitPolicyRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("혜택 이용 정책을 찾을 수 없습니다: " + code));
    }

    private List<Long> inactivateMissingPolicies(
            Carrier carrier,
            Set<String> incomingSourceKeys,
            Set<com.itplace.userapi.benefit.entity.enums.MainCategory> incomingCategories,
            LocalDateTime crawledAt
    ) {
        if (incomingCategories.isEmpty()) return List.of();
        List<BenefitCarrierPolicy> missingPolicies = benefitCarrierPolicyRepository.findAllByCarrier(carrier).stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .filter(policy -> policy.getBenefit() != null
                        && incomingCategories.contains(policy.getBenefit().getMainCategory()))
                .filter(policy -> !incomingSourceKeys.contains(policy.getSourceKey()))
                .peek(policy -> {
                    policy.setActive(false);
                    policy.setLastCrawledAt(crawledAt);
                })
                .toList();
        if (!missingPolicies.isEmpty()) {
            benefitCarrierPolicyRepository.saveAll(missingPolicies);
        }
        return missingPolicies.stream()
                .map(policy -> policy.getBenefit().getPartner())
                .filter(Objects::nonNull)
                .map(Partner::getPartnerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void replaceCarrierTierBenefits(
            List<BenefitCarrierPolicy> savedPolicies,
            Map<String, ImportRow> latestRowBySourceKey
    ) {
        if (savedPolicies.isEmpty()) {
            return;
        }

        List<CarrierTierBenefit> existingTiers = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(savedPolicies);
        Map<Long, List<CarrierTierBenefit>> tiersByPolicy = existingTiers.stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
        List<CarrierTierBenefit> removals = new ArrayList<>();
        List<CarrierTierBenefit> replacements = new ArrayList<>();
        Map<String, BenefitCarrierPolicy> savedPolicyBySourceKey = savedPolicies.stream()
                .collect(Collectors.toMap(BenefitCarrierPolicy::getSourceKey, Function.identity(), (first, second) -> second));
        latestRowBySourceKey.forEach((sourceKey, row) -> {
            BenefitCarrierPolicy savedPolicy = savedPolicyBySourceKey.get(sourceKey);
            if (savedPolicy != null) {
                List<CarrierTierBenefit> current = tiersByPolicy.getOrDefault(savedPolicy.getBenefitCarrierPolicyId(), List.of());
                List<CarrierTierBenefit> incoming = new ArrayList<>(createCarrierTierBenefits(savedPolicy, row.item().getTierBenefits()));
                List<CarrierTierBenefit> unused = new ArrayList<>(current);
                for (int index = 0; index < incoming.size(); index++) {
                    CarrierTierBenefit next = incoming.get(index);
                    CarrierTierBenefit exact = unused.stream().filter(old -> sameTier(old, next)).findFirst().orElse(null);
                    if (exact != null) { unused.remove(exact); incoming.set(index, exact); continue; }
                    List<CarrierTierBenefit> candidates = unused.stream()
                            .filter(old -> Objects.equals(old.getGrade(), next.getGrade()))
                            .filter(old -> Objects.equals(old.getOfferKey(), next.getOfferKey()))
                            .toList();
                    if (candidates.size() == 1) {
                        CarrierTierBenefit rowToUpdate = candidates.get(0);
                        rowToUpdate.setContext(next.getContext()); rowToUpdate.setIsAll(next.getIsAll());
                        rowToUpdate.setDiscountValue(next.getDiscountValue());
                        replacements.add(rowToUpdate);
                        unused.remove(rowToUpdate);
                    } else if (candidates.isEmpty() && StringUtils.hasText(next.getOfferKey())) {
                        List<CarrierTierBenefit> legacyMatches = unused.stream()
                                .filter(old -> old.getOfferKey() == null || isLegacyPositionOfferKey(old.getOfferKey()))
                                .filter(old -> sameTierContent(old, next))
                                .toList();
                        if (legacyMatches.size() == 1) {
                            CarrierTierBenefit rowToUpdate = legacyMatches.get(0);
                            rowToUpdate.setOfferKey(next.getOfferKey());
                            replacements.add(rowToUpdate);
                            unused.remove(rowToUpdate);
                        } else {
                            replacements.add(next);
                        }
                    } else {
                        replacements.add(next);
                    }
                }
                removals.addAll(unused);
            }
        });
        if (!removals.isEmpty()) {
            carrierTierBenefitRepository.deleteAll(removals);
        }
        if (!replacements.isEmpty()) {
            carrierTierBenefitRepository.saveAll(replacements);
        }
    }

    private boolean sameTier(CarrierTierBenefit first, CarrierTierBenefit second) {
        return Objects.equals(first.getGrade(), second.getGrade()) && Objects.equals(first.getContext(), second.getContext())
                && Objects.equals(first.getIsAll(), second.getIsAll())
                && Objects.equals(first.getDiscountValue(), second.getDiscountValue())
                && Objects.equals(first.getOfferKey(), second.getOfferKey());
    }

    private List<CarrierTierBenefit> createCarrierTierBenefits(
            BenefitCarrierPolicy policy,
            List<BenefitSnapshotImportRequest.TierBenefitSnapshot> tierBenefits
    ) {
        if (tierBenefits == null || tierBenefits.isEmpty()) {
            return List.of();
        }

        Map<String, Set<Grade>> gradesByOfferContext = tierBenefits.stream()
                .filter(tier -> tier.getContext() != null && !tier.getContext().isBlank())
                .collect(Collectors.groupingBy(tier -> tierGroupKey(resolveOfferKey(policy, tier), tier.getContext()),
                        Collectors.mapping(BenefitSnapshotImportRequest.TierBenefitSnapshot::getGrade,
                                Collectors.toSet())));
        Set<String> allGradeOfferContexts = TierBenefitSemantics.allGradeContexts(gradesByOfferContext,
                referenceDataService.allGradeMembers(policy.getCarrier()));

        return tierBenefits.stream()
                .map(tier -> {
                    String offerKey = resolveOfferKey(policy, tier);
                    return CarrierTierBenefit.builder()
                            .benefitCarrierPolicy(policy)
                            .grade(tier.getGrade())
                            .context(tier.getContext())
                            .isAll(allGradeOfferContexts.contains(tierGroupKey(offerKey, tier.getContext())))
                            .discountValue(tier.getDiscountValue())
                            .offerKey(offerKey)
                            .build();
                })
                .toList();
    }

    private String tierGroupKey(String offerKey, String context) {
        return Objects.toString(offerKey, "") + "\u0000" + Objects.toString(context, "");
    }

    private boolean isLegacyPositionOfferKey(String key) {
        return key != null && key.matches("offer-\\d+");
    }

    private boolean sameTierContent(CarrierTierBenefit first, CarrierTierBenefit second) {
        return Objects.equals(first.getGrade(), second.getGrade())
                && Objects.equals(first.getContext(), second.getContext())
                && Objects.equals(first.getIsAll(), second.getIsAll())
                && Objects.equals(first.getDiscountValue(), second.getDiscountValue());
    }

    private String resolveOfferKey(BenefitCarrierPolicy policy,
            BenefitSnapshotImportRequest.TierBenefitSnapshot tier) {
        var conditions = policy.getConditions();
        if (conditions == null || tier.getGrade() == null || !StringUtils.hasText(tier.getContext())) return null;
        List<com.itplace.userapi.benefit.dto.BenefitOffer> wholeMatches = conditions.offers().stream()
                .filter(offer -> Boolean.TRUE.equals(offer.allGrades())
                        || offer.gradeCodes().contains(tier.getGrade().getCode()))
                .filter(offer -> tierContextMatches(offer, tier.getContext(), tier.getGrade()))
                .toList();
        if (StringUtils.hasText(tier.getOfferKey())) {
            long keyedMatches = wholeMatches.stream()
                    .filter(offer -> Objects.equals(offer.key(), tier.getOfferKey())).count();
            if (keyedMatches == 1) return tier.getOfferKey();
        } else if (wholeMatches.size() == 1) {
            return wholeMatches.get(0).key();
        }
        List<String> clauses = tierClauses(tier.getContext());
        if (clauses.size() > 1) {
            List<com.itplace.userapi.benefit.dto.BenefitOffer> mapped = mapTierClauses(conditions.offers(), tier.getGrade(), clauses);
            if (mapped.size() != clauses.size() || mapped.stream().map(com.itplace.userapi.benefit.dto.BenefitOffer::key).distinct().count() != 1)
                return null;
            String key = mapped.get(0).key();
            return !StringUtils.hasText(tier.getOfferKey()) || Objects.equals(key, tier.getOfferKey()) ? key : null;
        }
        return null;
    }

    private boolean tierContextMatches(com.itplace.userapi.benefit.dto.BenefitOffer offer, String context, Grade grade) {
        String normalizedContext = normalizeTierEvidence(context);
        if (normalizedContext.isEmpty()) return false;
        return (offer.title() != null && normalizeTierEvidence(offer.title()).equals(normalizedContext))
                || (offer.sourceText() != null
                        && normalizeTierEvidence(offer.sourceText()).contains(normalizedContext))
                || labeledTierContextMatches(offer, context, grade);
    }

    private boolean labeledTierContextMatches(com.itplace.userapi.benefit.dto.BenefitOffer offer, String context, Grade grade) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\s*\\[([^]]+)]\\s*(.+)$", java.util.regex.Pattern.DOTALL)
                .matcher(context);
        if (!matcher.matches() || offer.title() == null) return false;
        String label = normalizeTierEvidence(matcher.group(1));
        String title = offer.title();
        boolean titleMatches = normalizeTierEvidence(title).equals(label);
        int suffixStart = title.lastIndexOf(" — ");
        if (!titleMatches && suffixStart >= 0) {
            String gradeSuffix = gradeSuffix(grade);
            titleMatches = !gradeSuffix.isEmpty()
                    && normalizeTierEvidence(title.substring(0, suffixStart)).equals(label)
                    && normalizeTierEvidence(title.substring(suffixStart + 3)).equals(normalizeTierEvidence(gradeSuffix));
        }
        return titleMatches && offer.sourceText() != null
                && normalizeTierEvidence(offer.sourceText()).contains(normalizeTierEvidence(matcher.group(2)));
    }

    private String gradeSuffix(Grade grade) {
        String code = grade == null ? "" : grade.getCode();
        return code.substring(code.lastIndexOf('_') + 1);
    }

    private List<com.itplace.userapi.benefit.dto.BenefitOffer> mapTierClauses(
            List<com.itplace.userapi.benefit.dto.BenefitOffer> offers, Grade grade, List<String> clauses) {
        List<com.itplace.userapi.benefit.dto.BenefitOffer> mapped = new ArrayList<>();
        for (String clause : clauses) {
            String normalized = normalizeTierEvidence(clause);
            if (normalized.isEmpty() || (normalized.matches(".*(?:불가|불가능|제외|미적용|사용할 수 없|이용할 수 없).*")
                    && !normalized.matches(".*(?:할인|적립|가능|무료|제공|증정|예매|티켓|쿠폰|이용권).*"))) return List.of();
            List<com.itplace.userapi.benefit.dto.BenefitOffer> matches = offers.stream()
                    .filter(offer -> Boolean.TRUE.equals(offer.allGrades()) || offer.gradeCodes().contains(grade.getCode()))
                    .filter(offer -> offer.sourceText() != null
                            && normalizeTierEvidence(offer.sourceText()).contains(normalized))
                    .toList();
            if (matches.size() != 1) return List.of();
            mapped.add(matches.get(0));
        }
        return mapped;
    }

    private List<String> tierClauses(String context) {
        if (!StringUtils.hasText(context)) return List.of();
        String htmlNormalized = context.replaceAll("(?i)</?br\\s*/?>", "\n");
        List<String> clauses = java.util.Arrays.stream(htmlNormalized.split("\\R|(?=\\s*\\[[^]]+])"))
                .map(String::trim).filter(StringUtils::hasText).toList();
        if (clauses.size() > 1 && clauses.stream().allMatch(this::hasTierAction)) return clauses;
        clauses = java.util.Arrays.stream(context.split("\\s*(?:\\+|＋|및|그리고)\\s*"))
                .map(String::trim).filter(StringUtils::hasText).toList();
        if (clauses.size() > 1 && clauses.stream().allMatch(this::hasTierAction)) return clauses;
        clauses = java.util.Arrays.stream(context.split("(?<![0-9]),(?![0-9])|，"))
                .map(String::trim).filter(StringUtils::hasText).toList();
        return clauses.size() > 1 && clauses.stream().allMatch(this::hasTierAction) ? clauses : List.of();
    }

    private boolean hasTierAction(String clause) {
        String value = normalizeTierEvidence(clause);
        return value.contains("할인") || value.contains("적립") || value.contains("캐시백")
                || value.contains("부여") || value.contains("포인트사용")
                || value.contains("예매") || value.contains("무료") || value.contains("제공")
                || value.contains("증정") || value.contains("이용권") || value.contains("쿠폰");
    }

    private String normalizeTierEvidence(String value) {
        return value == null ? "" : value.replaceAll("(?i)</?br\\s*/?>", " ")
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").trim();
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
            BenefitCarrierPolicy policy,
            boolean preserveHeld,
            boolean preserveArchived
    ) {
    }

    private record BenefitKey(Long partnerId, String canonicalKey) {
    }
}
