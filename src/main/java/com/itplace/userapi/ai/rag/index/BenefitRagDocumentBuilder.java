package com.itplace.userapi.ai.rag.index;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadata;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.support.BenefitContextSplitter;
import com.itplace.userapi.benefit.service.TierBenefitSemantics;
import com.itplace.userapi.benefit.service.BenefitOfferMatcher;
import com.itplace.userapi.benefit.service.BenefitFactFormatter;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Map;
import java.util.EnumMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BenefitRagDocumentBuilder {
    static final String EMBEDDING_VERSION = "benefit-carrier-grade-rag-v3";
    static final String SYNC_STATUS_ACTIVE = "ACTIVE";
    static final String SYNC_STATUS_INACTIVE = "INACTIVE";

    private final BenefitRagMetadataClassifier metadataClassifier;
    @Autowired private ReferenceDataService referenceDataService;
    @Autowired(required = false) private BenefitOfferMatcher benefitOfferMatcher;

    public List<PendingBenefitDocument> buildPendingDocuments(Benefit benefit, List<BenefitCarrierPolicy> policies, List<CarrierTierBenefit> tierBenefits) {
        if (benefit.getPartner() == null) {
            return List.of();
        }

        if (policies.isEmpty()) {
            return List.of();
        }

        Map<Carrier, Map<String, String>> labels = new EnumMap<>(Carrier.class);
        Map<Carrier, List<com.itplace.userapi.benefit.entity.enums.Grade>> allGrades = new EnumMap<>(Carrier.class);
        Map<String, String> categoryLabels = new java.util.HashMap<>();
        Map<String, String> programLabels = new java.util.HashMap<>();
        if (referenceDataService != null) {
            var reference = referenceDataService.getReferenceData();
            reference.categories().forEach(item -> categoryLabels.put(item.code(), item.label()));
            reference.programs().forEach(item -> programLabels.put(item.code(), item.label()));
            reference.grades().forEach(item -> labels.computeIfAbsent(Carrier.valueOf(item.carrier()), ignored -> new java.util.HashMap<>()).put(item.code(), item.label()));
            reference.grades().stream().filter(item -> item.active() && item.allGradeMember()).forEach(item ->
                    allGrades.computeIfAbsent(Carrier.valueOf(item.carrier()), ignored -> new ArrayList<>()).add(
                            com.itplace.userapi.benefit.entity.enums.Grade.of(item.code())));
        }
        return policies.stream()
                .flatMap(policy -> pendingDocumentsForPolicy(benefit, policy, tierBenefits, labels,
                        allGrades.getOrDefault(policy.getCarrier(), List.of()), categoryLabels, programLabels).stream())
                .toList();
    }

    private List<PendingBenefitDocument> pendingDocumentsForPolicy(Benefit benefit,
                                                                   BenefitCarrierPolicy policy,
                                                                   List<CarrierTierBenefit> allTierBenefits,
                                                                   Map<Carrier, Map<String, String>> labels,
                                                                   List<com.itplace.userapi.benefit.entity.enums.Grade> allGrades,
                                                                   Map<String, String> categoryLabels, Map<String, String> programLabels) {
        List<CarrierTierBenefit> policyTiers = allTierBenefits.stream()
                .filter(tier -> Objects.equals(tier.getBenefitCarrierPolicy(), policy)
                        || (tier.getBenefitCarrierPolicy() != null
                        && Objects.equals(tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId(),
                        policy.getBenefitCarrierPolicyId())))
                .filter(tier -> tier.getOfferKey() == null || validOfferKey(tier.getOfferKey()))
                .toList();

        if (policyTiers.isEmpty()) {
            List<PendingBenefitDocument> documents = new ArrayList<>();
            if (benefitOfferMatcher == null || !benefitOfferMatcher.hasKnownOffers(policy.getConditions())) {
                PendingBenefitDocument document = toPendingDocument(benefit, policy, null, labels,
                        java.util.Set.of(), categoryLabels, programLabels);
                if (document != null) documents.add(document);
            }
            documents.addAll(offerDocumentsWithoutTier(benefit, policy, List.of(), labels, categoryLabels, programLabels));
            return documents;
        }

        var allContexts = allGradeContextsByOffer(policyTiers, allGrades);
        List<PendingBenefitDocument> documents = new ArrayList<>(policyTiers.stream()
                .map(tierBenefit -> toPendingDocument(benefit, policy, tierBenefit, labels, allContexts, categoryLabels, programLabels))
                .filter(Objects::nonNull)
                .toList());
        documents.addAll(offerDocumentsWithoutTier(benefit, policy, policyTiers, labels, categoryLabels, programLabels));
        return List.copyOf(documents);
    }

    private List<PendingBenefitDocument> offerDocumentsWithoutTier(Benefit benefit, BenefitCarrierPolicy policy,
                                                                    List<CarrierTierBenefit> tiers,
                                                                    Map<Carrier, Map<String, String>> labels,
                                                                    Map<String, String> categoryLabels,
                                                                    Map<String, String> programLabels) {
        if (benefitOfferMatcher == null || !benefitOfferMatcher.hasKnownOffers(policy.getConditions())) return List.of();
        List<com.itplace.userapi.reference.ReferenceData.Grade> benefitGrades = referenceDataService == null ? List.of()
                : referenceDataService.getReferenceData().grades().stream()
                .filter(g -> g.active() && g.benefitEnabled() && policy.getCarrier().name().equals(g.carrier()))
                .toList();
        List<PendingBenefitDocument> documents = new ArrayList<>();
        for (var offer : policy.getConditions().offers()) {
            if (!validOfferKey(offer.key())) continue;
            List<com.itplace.userapi.benefit.entity.enums.Grade> targetGrades = Boolean.TRUE.equals(offer.allGrades())
                    ? benefitGrades.stream().map(item -> resolveOfferGrade(policy.getCarrier(), item.code()))
                            .filter(Objects::nonNull).distinct().toList()
                    : offer.gradeCodes().stream().map(code -> resolveOfferGrade(policy.getCarrier(), code))
                            .filter(Objects::nonNull).distinct().toList();
            for (var grade : targetGrades) {
                boolean covered = tiers.stream().anyMatch(tier -> validOfferKey(tier.getOfferKey())
                        && offer.key().equals(tier.getOfferKey()) && grade.equals(tier.getGrade())
                        && benefitOfferMatcher.scope(policy.getConditions().withOffers(List.of(offer)), null,
                                policy.getCarrier(), List.of(grade)).offers().size() == 1);
                if (covered) continue;
                documents.add(toOfferDocument(benefit, policy, offer, grade.getCode(),
                        Boolean.TRUE.equals(offer.allGrades()), labels, categoryLabels, programLabels));
            }
        }
        return documents.stream().filter(Objects::nonNull).toList();
    }

    private boolean validOfferKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,80}");
    }

    private com.itplace.userapi.benefit.entity.enums.Grade resolveOfferGrade(Carrier carrier, String code) {
        if (referenceDataService != null) {
            var alias = referenceDataService.resolveGradeAlias(carrier, code);
            if (alias != null && referenceDataService.isBenefitGrade(carrier, alias)) return alias;
        }
        try {
            var grade = com.itplace.userapi.benefit.entity.enums.Grade.of(code);
            return referenceDataService == null || referenceDataService.isBenefitGrade(carrier, grade) ? grade : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private PendingBenefitDocument toOfferDocument(Benefit benefit, BenefitCarrierPolicy policy,
                                                    BenefitOffer offer,
                                                    String gradeCode, boolean allGrades,
                                                    Map<Carrier, Map<String, String>> labels,
                                                    Map<String, String> categoryLabels,
                                                    Map<String, String> programLabels) {
        return toPendingDocument(benefit, policy, null, labels, java.util.Set.of(), categoryLabels,
                programLabels, offer, gradeCode, allGrades);
    }

    private PendingBenefitDocument toPendingDocument(Benefit benefit,
                                                     BenefitCarrierPolicy policy,
                                                     CarrierTierBenefit tierBenefit,
                                                     Map<Carrier, Map<String, String>> labels,
                                                     java.util.Set<String> allGradeContexts,
                                                     Map<String, String> categoryLabels, Map<String, String> programLabels) {
        return toPendingDocument(benefit, policy, tierBenefit, labels, allGradeContexts, categoryLabels,
                programLabels, null, null, false);
    }

    private PendingBenefitDocument toPendingDocument(Benefit benefit,
                                                     BenefitCarrierPolicy policy,
                                                     CarrierTierBenefit tierBenefit,
                                                     Map<Carrier, Map<String, String>> labels,
                                                     java.util.Set<String> allGradeContexts,
                                                     Map<String, String> categoryLabels, Map<String, String> programLabels,
                                                     BenefitOffer sourceOffer, String sourceGradeCode, boolean sourceAllGrades) {
        String description = nullToBlank(policy.getDescription());
        String manual = nullToBlank(policy.getManual());
        String tierContext = tierBenefit == null ? "" : nullToBlank(tierBenefit.getContext());
        BenefitConditions scopedConditions = sourceOffer != null ? policy.getConditions().withOffers(List.of(sourceOffer))
                : conditionsForTier(policy, tierBenefit, allGradeContexts);
        boolean resolvedOffers = benefitOfferMatcher != null && benefitOfferMatcher.hasKnownOffers(policy.getConditions());
        boolean unresolvedOffers = policy.getConditions() != null
                && policy.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.UNRESOLVED;
        boolean scopedResolution = resolvedOffers
                && policy.getConditions().offerResolution() != com.itplace.userapi.benefit.dto.OfferResolution.SINGLE;
        String scopedSummary = scopedResolution ? offerSummary(scopedConditions) : "";
        String scopedDetails = scopedResolution ? offerDetails(scopedConditions) : "";
        String singleOfferSummary = resolvedOffers && !scopedResolution ? safeSingleOfferSummary(scopedConditions) : "";
        String documentContext = scopedResolution ? String.join(" ",
                java.util.stream.Stream.of(tierContext, scopedSummary).filter(value -> !value.isBlank()).toList()) : tierContext;
        String documentDescription = unresolvedOffers ? "개별 혜택 및 이용 채널 구분 확인 필요"
                : scopedResolution && policy.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL
                ? "확인된 개별 혜택 일부. 원문 중 연결되지 않은 조건은 별도 확인 필요. " + tierContext + " " + scopedSummary
                : scopedResolution ? scopedSummary : resolvedOffers ? singleOfferSummary : description;
        String documentManual = unresolvedOffers ? "" : resolvedOffers ? offerDetails(scopedConditions) : manual;
        String documentUsageType = unresolvedOffers ? "UNKNOWN" : resolvedOffers ? offerChannel(scopedConditions) : enumName(policy.getUsageType());
        String documentSourceLimit = resolvedOffers ? offerLimits(scopedConditions) : nullToBlank(policy.getSourceLimitText());
        BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(documentContext);
        String onlineContext = scopedResolution ? "" : nullToBlank(splitContext.onlineContext());
        String offlineContext = scopedResolution ? "" : nullToBlank(splitContext.offlineContext());
        BenefitRagMetadata metadata = metadataClassifier.classify(
                benefit.getPartner().getPartnerName(), benefit.getPartner().getCategory(),
                benefit.getMainCategory() == null ? "" : programLabels.getOrDefault(benefit.getMainCategory().getCode(), benefit.getMainCategory().getCode()),
                benefit.getBenefitName(), documentDescription, documentManual, documentContext, policy.getSourceCategory());
        String searchableText = searchableText(benefit, policy, tierBenefit, description, manual, documentContext,
                scopedConditions, resolvedOffers, unresolvedOffers, metadata, labels, allGradeContexts, categoryLabels, programLabels);

        if (searchableText.isBlank()) {
            return null;
        }

        String documentId = sourceOffer == null ? documentId(benefit, policy, tierBenefit)
                : documentId(benefit, policy, sourceOffer.key(), sourceGradeCode);
        String sourceUpdatedAt = latestSourceUpdatedAt(benefit, policy);
        boolean active = !Boolean.FALSE.equals(benefit.getActive()) && !Boolean.FALSE.equals(policy.getActive());
        BenefitDocument document = BenefitDocument.builder()
                .id(documentId)
                .documentId(documentId)
                .partnerId(String.valueOf(benefit.getPartner().getPartnerId()))
                .partnerName(nullToBlank(benefit.getPartner().getPartnerName()))
                .benefitId(String.valueOf(benefit.getBenefitId()))
                .benefitName(nullToBlank(benefit.getBenefitName()))
                .policyId(String.valueOf(policy.getBenefitCarrierPolicyId()))
                .tierBenefitId(tierBenefit == null ? null : String.valueOf(tierBenefit.getCarrierTierBenefitId()))
                .offerKey(sourceOffer != null ? sourceOffer.key()
                        : tierBenefit == null ? null : blankToNull(tierBenefit.getOfferKey()))
                .carrierBenefitName(nullToBlank(policy.getCarrierBenefitName()))
                .category(nullToBlank(benefit.getPartner().getCategory()))
                .mainCategory(benefit.getMainCategory() == null ? "" : benefit.getMainCategory().getCode())
                .carrier(enumName(policy.getCarrier()))
                .grade(tierBenefit != null ? enumName(tierBenefit.getGrade()) : sourceGradeCode)
                .isAllGrade(tierBenefit != null ? allGradeContexts.contains(allGradeContextKey(tierBenefit)) : sourceAllGrades)
                .usageType(documentUsageType)
                .benefitType(enumName(policy.getType()))
                .active(active)
                .sourceKey(nullToBlank(policy.getSourceKey()))
                .sourceEvidenceId(policy.getSourceEvidenceId())
                .sourceUrl(nullToBlank(firstNonBlank(policy.getSourceUrl(), policy.getUrl())))
                .sourceCategory(nullToBlank(policy.getSourceCategory()))
                .lastCrawledAt(policy.getLastCrawledAt() == null ? null : policy.getLastCrawledAt().toString())
                .sourceUpdatedAt(blankToNull(sourceUpdatedAt))
                .sourceCapturedAt(policy.getSourceCapturedAt() == null ? null : policy.getSourceCapturedAt().toString())
                .sourceLimitText(documentSourceLimit)
                .membershipOptionType(enumName(policy.getMembershipOptionType()))
                .sourceOptionLabel(nullToBlank(policy.getSourceOptionLabel()))
                .conditionProducts(unresolvedOffers || scopedConditions == null ? List.of() : scopedConditions.products())
                .conditionAvailability(unresolvedOffers || scopedConditions == null ? List.of() : scopedConditions.availability())
                .conditionLimits(unresolvedOffers || scopedConditions == null ? List.of() : scopedConditions.limits())
                .conditionUsage(unresolvedOffers || scopedConditions == null ? List.of() : scopedConditions.usage())
                .conditionExclusions(unresolvedOffers || scopedConditions == null ? List.of() : scopedConditions.exclusions())
                .embeddingVersion(EMBEDDING_VERSION)
                .contentHash(contentHash(searchableText))
                .syncStatus(active ? SYNC_STATUS_ACTIVE : SYNC_STATUS_INACTIVE)
                .description(documentDescription)
                .manual(documentManual)
                .context(documentContext)
                .tierContext(documentContext)
                .onlineContext(blankToNull(onlineContext))
                .offlineContext(blankToNull(offlineContext))
                .discountValue(tierBenefit == null || scopedResolution ? null : tierBenefit.getDiscountValue())
                .businessType(metadata.businessType())
                .useCases(metadata.useCases())
                .negativeUseCases(metadata.negativeUseCases())
                .tags(metadata.tags())
                .build();
        return new PendingBenefitDocument(document, searchableText);
    }

    private String searchableText(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tierBenefit,
                                  String description, String manual, String tierContext,
                                  BenefitConditions conditions, boolean resolvedOffers, boolean unresolvedOffers,
                                  BenefitRagMetadata metadata,
                                  Map<Carrier, Map<String, String>> labels, java.util.Set<String> allGradeContexts,
                                  Map<String, String> categoryLabels, Map<String, String> programLabels) {
        List<String> lines = new ArrayList<>();
        addLabeled(lines, "제휴처", benefit.getPartner().getPartnerName());
        addLabeled(lines, "제휴처 업종", com.itplace.userapi.ai.rag.metadata.BenefitBusinessTypeLabels.koreanLabel(metadata.businessType()));
        if (metadata.useCases().size() > 0) addLabeled(lines, "이용 목적", String.join(" ", metadata.useCases()));
        addLabeled(lines, "제휴처 분야", categoryLabels.getOrDefault(benefit.getPartner().getCategory(), benefit.getPartner().getCategory()));
        if (benefit.getMainCategory() != null) addLabeled(lines, "혜택 프로그램", programLabels.getOrDefault(
                benefit.getMainCategory().getCode(), benefit.getMainCategory().getCode()));
        addLabeled(lines, "혜택명", benefit.getBenefitName());
        addLabeled(lines, "통신사별 혜택명", policy.getCarrierBenefitName());
        addLabeled(lines, "통신사", enumName(policy.getCarrier()));
        if (tierBenefit != null) {
            addLabeled(lines, "대상 등급", allGradeContexts.contains(allGradeContextKey(tierBenefit)) ? "전체 등급" : gradeLabel(policy.getCarrier(), tierBenefit.getGrade(), labels));
        }
        if (!unresolvedOffers && policy.getUsageType() != null && !"UNKNOWN".equals(enumName(policy.getUsageType()))) {
            addLabeled(lines, "이용 채널", enumName(policy.getUsageType()));
        }
        if (!resolvedOffers && !unresolvedOffers) {
            addLabeled(lines, "혜택 설명", description);
            addLabeled(lines, "혜택 세부 내용", manualBenefitFacts(manual));
        }
        if (unresolvedOffers) addLabeled(lines, "확인 필요", "개별 혜택과 이용 채널 구분");

        LinkedHashSet<String> conditionFacts = new LinkedHashSet<>();
        if (conditions != null) {
            if (!resolvedOffers && !unresolvedOffers) {
                addFact(conditionFacts, policy.getSourceLimitText());
                addFacts(conditionFacts, conditions.products());
                addFacts(conditionFacts, conditions.availability());
                addFacts(conditionFacts, conditions.limits());
                addFacts(conditionFacts, conditions.usage());
                addFacts(conditionFacts, conditions.exclusions());
                addFact(conditionFacts, policy.getSourceLimitText());
            }
            for (var offer : resolvedOffers ? conditions.offers() : List.<com.itplace.userapi.benefit.dto.BenefitOffer>of()) {
                String scope = offer.usageType() == null ? "채널 미확정" : offer.usageType().name();
                String facts = java.util.stream.Stream.of(offer.title(),
                                conditions.offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE ? null : offer.sourceText())
                        .filter(value -> value != null && !value.isBlank()).distinct()
                        .collect(java.util.stream.Collectors.joining(" "));
                if (!facts.isBlank()) addLabeled(lines, "개별 혜택 " + scope, facts);
                for (String fact : java.util.stream.Stream.of(offer.products(), offer.availability(), offer.limits(),
                                offer.usage(), offer.exclusions(), offer.channelEvidence(), BenefitFactFormatter.format(offer.facts()))
                        .flatMap(List::stream).distinct().toList()) {
                    addLabeled(lines, "개별 혜택 " + scope + " 조건", fact);
                }
            }
        }
        conditionFacts.forEach(fact -> addLabeled(lines, "대상 및 한도", fact));
        addLabeled(lines, "등급별 혜택", tierContext);
        return String.join("\n", lines);
    }

    private BenefitConditions conditionsForTier(BenefitCarrierPolicy policy, CarrierTierBenefit tier,
                                                 java.util.Set<String> allGradeContexts) {
        BenefitConditions conditions = policy.getConditions();
        if (conditions == null || benefitOfferMatcher == null || !benefitOfferMatcher.hasKnownOffers(conditions)) return conditions;
        if (tier == null || tier.getOfferKey() == null || tier.getOfferKey().isBlank()) return conditions.withOffers(List.of());
        List<BenefitOffer> linked = conditions.offers().stream()
                .filter(offer -> tier.getOfferKey().equals(offer.key()))
                .filter(offer -> benefitOfferMatcher.scope(conditions.withOffers(List.of(offer)), null,
                        policy.getCarrier(), List.of(tier.getGrade())).offers().size() == 1)
                .toList();
        return conditions.withOffers(linked.size() == 1 ? linked : List.of());
    }

    private java.util.Set<String> allGradeContextsByOffer(List<CarrierTierBenefit> tiers,
                                                           List<com.itplace.userapi.benefit.entity.enums.Grade> allGrades) {
        return tiers.stream().collect(java.util.stream.Collectors.groupingBy(this::allGradeContextKey))
                .entrySet().stream().filter(entry -> {
                    List<CarrierTierBenefit> rows = entry.getValue();
                    return TierBenefitSemantics.allGradeContexts(rows, allGrades).contains(rows.get(0).getContext());
                }).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    private String allGradeContextKey(CarrierTierBenefit tier) {
        return Objects.toString(tier.getOfferKey(), "") + "\u0000" + Objects.toString(tier.getContext(), "");
    }

    private String offerSummary(BenefitConditions conditions) {
        if (conditions == null) return "";
        return conditions.offers().stream().map(offer -> java.util.stream.Stream.of(offer.title(), offer.sourceText())
                        .filter(value -> value != null && !value.isBlank())
                        .collect(java.util.stream.Collectors.joining(" ")) + " "
                        + String.join(" ", BenefitFactFormatter.format(offer.facts())))
                .filter(value -> !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining(" / "));
    }

    private String safeSingleOfferSummary(BenefitConditions conditions) {
        if (conditions == null) return "";
        return conditions.offers().stream().map(offer -> java.util.stream.Stream.of(offer.title(),
                        String.join(" ", offer.products()), String.join(" ", BenefitFactFormatter.format(offer.facts())))
                        .filter(value -> value != null && !value.isBlank()).distinct()
                        .collect(java.util.stream.Collectors.joining(" ")))
                .filter(value -> !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining(" / "));
    }

    private String offerDetails(BenefitConditions conditions) {
        if (conditions == null) return "";
        return conditions.offers().stream().flatMap(offer -> java.util.stream.Stream.of(offer.products(), offer.availability(),
                        offer.limits(), offer.usage(), offer.exclusions(), BenefitFactFormatter.format(offer.facts())).flatMap(List::stream))
                .filter(value -> value != null && !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String offerLimits(BenefitConditions conditions) {
        if (conditions == null) return "";
        return conditions.offers().stream().flatMap(offer -> java.util.stream.Stream.concat(offer.limits().stream(),
                        BenefitFactFormatter.format(offer.facts()).stream()))
                .filter(value -> value != null && !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String offerChannel(BenefitConditions conditions) {
        if (conditions == null) return "UNKNOWN";
        List<com.itplace.userapi.benefit.entity.enums.UsageType> channels = conditions.offers().stream()
                .map(com.itplace.userapi.benefit.dto.BenefitOffer::usageType).distinct().toList();
        if (channels.contains(null)) return "UNKNOWN";
        boolean online = channels.contains(com.itplace.userapi.benefit.entity.enums.UsageType.ONLINE)
                || channels.contains(com.itplace.userapi.benefit.entity.enums.UsageType.BOTH);
        boolean offline = channels.contains(com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE)
                || channels.contains(com.itplace.userapi.benefit.entity.enums.UsageType.BOTH);
        if (online && offline) return "BOTH";
        if (online) return "ONLINE";
        if (offline) return "OFFLINE";
        return "UNKNOWN";
    }

    private String manualBenefitFacts(String manual) {
        if (manual == null || manual.isBlank()) return "";
        int end = manual.length();
        for (String heading : List.of("■ 이용 방법", "■ 이용방법", "■ 유의 사항", "■ 유의사항", "이용 방법", "이용방법", "유의 사항", "유의사항",
                "쿠폰 다운로드 방법", "쿠폰 사용 방법", "다운로드 방법", "사용 방법", "구매 방법")) {
            int index = manual.indexOf(heading);
            if (index >= 0) end = Math.min(end, index);
        }
        String lead = manual.substring(0, end).replaceAll("(?s)<[^>]*>", " ").trim();
        StringBuilder facts = new StringBuilder();
        for (String line : lead.split("[\\r\\n]+|(?<=%)\\s+(?=[-①-⑩])")) {
            String fact = line.trim();
            String normalized = fact.replaceAll("\\s+", "");
            if (fact.isBlank() || normalized.matches("(?i).*(로그인|다운로드|쿠폰함|앱에서|앱내|클릭|선택|입력|등록완료).*")) continue;
            if (fact.length() > 240) continue;
            if (facts.length() > 0) facts.append(' ');
            facts.append(fact);
            if (facts.length() >= 600) break;
        }
        return facts.toString();
    }

    private void addFacts(LinkedHashSet<String> values, List<String> source) {
        if (source != null) source.forEach(value -> addFact(values, value));
    }

    private void addFact(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private void addLabeled(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + ": " + value);
    }

    private String latestSourceUpdatedAt(Benefit benefit, BenefitCarrierPolicy policy) {
        if (policy.getLastModifiedDate() != null) {
            return policy.getLastModifiedDate().toString();
        }
        if (benefit.getLastModifiedDate() != null) {
            return benefit.getLastModifiedDate().toString();
        }
        if (policy.getLastCrawledAt() != null) {
            return policy.getLastCrawledAt().toString();
        }
        return "";
    }

    private String contentHash(String searchableText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", EMBEDDING_VERSION, searchableText)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private String documentId(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tierBenefit) {
        String benefitId = String.valueOf(benefit.getBenefitId());
        String policyId = String.valueOf(policy.getBenefitCarrierPolicyId());
        String tierId = tierBenefit == null ? "policy" : String.valueOf(tierBenefit.getCarrierTierBenefitId());
        return "benefit:%s:policy:%s:tier:%s".formatted(benefitId, policyId, tierId);
    }

    private String documentId(Benefit benefit, BenefitCarrierPolicy policy, String offerKey, String gradeCode) {
        return "benefit:%s:policy:%s:offer:%s:grade:%s".formatted(
                benefit.getBenefitId(), policy.getBenefitCarrierPolicyId(), offerKey, gradeCode);
    }

    private String enumName(Object value) {
        if (value == null) return "";
        if (value instanceof com.itplace.userapi.benefit.entity.enums.Grade grade) return grade.getCode();
        if (value instanceof com.itplace.userapi.benefit.entity.enums.MainCategory category) return category.getCode();
        return value instanceof Enum<?> e ? e.name() : value.toString();
    }

    private String gradeLabel(Carrier carrier, com.itplace.userapi.benefit.entity.enums.Grade grade,
                              Map<Carrier, Map<String, String>> labels) {
        return grade == null ? "" : labels.getOrDefault(carrier, Map.of()).getOrDefault(grade.getCode(), grade.getCode());
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record PendingBenefitDocument(BenefitDocument document, String searchableText) {
        public BenefitDocument withEmbedding(List<Float> embedding) {
            document.setEmbedding(embedding);
            document.setIndexedAt(Instant.now().toString());
            document.setDeletedAt(Boolean.FALSE.equals(document.getActive()) ? document.getIndexedAt() : null);
            return document;
        }
    }
}
