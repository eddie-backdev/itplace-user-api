package com.itplace.userapi.ai.rag.index;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadata;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BenefitRagDocumentBuilder {
    static final String EMBEDDING_VERSION = "benefit-carrier-grade-rag-v1";
    static final String SYNC_STATUS_ACTIVE = "ACTIVE";
    static final String SYNC_STATUS_INACTIVE = "INACTIVE";

    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final BenefitRagMetadataClassifier metadataClassifier;

    public List<PendingBenefitDocument> buildPendingDocuments(Benefit benefit) {
        if (benefit.getPartner() == null) {
            return List.of();
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit));
        if (policies.isEmpty()) {
            return List.of();
        }

        List<CarrierTierBenefit> tierBenefits = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        return policies.stream()
                .flatMap(policy -> pendingDocumentsForPolicy(benefit, policy, tierBenefits).stream())
                .toList();
    }

    private List<PendingBenefitDocument> pendingDocumentsForPolicy(Benefit benefit,
                                                                   BenefitCarrierPolicy policy,
                                                                   List<CarrierTierBenefit> allTierBenefits) {
        List<CarrierTierBenefit> policyTiers = allTierBenefits.stream()
                .filter(tier -> Objects.equals(tier.getBenefitCarrierPolicy(), policy)
                        || (tier.getBenefitCarrierPolicy() != null
                        && Objects.equals(tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId(),
                        policy.getBenefitCarrierPolicyId())))
                .toList();

        if (policyTiers.isEmpty()) {
            PendingBenefitDocument document = toPendingDocument(benefit, policy, null);
            return document == null ? List.of() : List.of(document);
        }

        return policyTiers.stream()
                .map(tierBenefit -> toPendingDocument(benefit, policy, tierBenefit))
                .filter(Objects::nonNull)
                .toList();
    }

    private PendingBenefitDocument toPendingDocument(Benefit benefit,
                                                     BenefitCarrierPolicy policy,
                                                     CarrierTierBenefit tierBenefit) {
        String description = nullToBlank(policy.getDescription());
        String manual = nullToBlank(policy.getManual());
        String tierContext = tierBenefit == null ? "" : nullToBlank(tierBenefit.getContext());
        BenefitRagMetadata metadata = metadataClassifier.classify(
                benefit.getPartner().getPartnerName(),
                benefit.getPartner().getCategory(),
                enumName(benefit.getMainCategory()),
                benefit.getBenefitName(),
                description,
                manual,
                tierContext
        );
        String searchableText = String.join("\n",
                nullToBlank(benefit.getPartner().getPartnerName()),
                nullToBlank(benefit.getPartner().getCategory()),
                metadata.businessType(),
                String.join(" ", metadata.useCases()),
                String.join(" ", metadata.tags()),
                nullToBlank(benefit.getBenefitName()),
                nullToBlank(policy.getCarrierBenefitName()),
                enumName(policy.getCarrier()),
                tierBenefit == null ? "전체 등급" : enumName(tierBenefit.getGrade()),
                enumName(policy.getUsageType()),
                enumName(policy.getType()),
                description,
                manual,
                tierContext
        ).trim();

        if (searchableText.isBlank()) {
            return null;
        }

        String documentId = documentId(benefit, policy, tierBenefit);
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
                .carrierBenefitName(nullToBlank(policy.getCarrierBenefitName()))
                .category(nullToBlank(benefit.getPartner().getCategory()))
                .mainCategory(enumName(benefit.getMainCategory()))
                .carrier(enumName(policy.getCarrier()))
                .grade(tierBenefit == null ? null : enumName(tierBenefit.getGrade()))
                .isAllGrade(tierBenefit == null || Boolean.TRUE.equals(tierBenefit.getIsAll()))
                .usageType(enumName(policy.getUsageType()))
                .benefitType(enumName(policy.getType()))
                .active(active)
                .sourceKey(nullToBlank(policy.getSourceKey()))
                .sourceUrl(nullToBlank(firstNonBlank(policy.getSourceUrl(), policy.getUrl())))
                .sourceCategory(nullToBlank(policy.getSourceCategory()))
                .lastCrawledAt(policy.getLastCrawledAt() == null ? null : policy.getLastCrawledAt().toString())
                .sourceUpdatedAt(blankToNull(sourceUpdatedAt))
                .embeddingVersion(EMBEDDING_VERSION)
                .contentHash(contentHash(searchableText, sourceUpdatedAt, documentId))
                .syncStatus(active ? SYNC_STATUS_ACTIVE : SYNC_STATUS_INACTIVE)
                .description(description)
                .manual(manual)
                .context(tierContext)
                .tierContext(tierContext)
                .discountValue(tierBenefit == null ? null : tierBenefit.getDiscountValue())
                .businessType(metadata.businessType())
                .useCases(metadata.useCases())
                .negativeUseCases(metadata.negativeUseCases())
                .tags(metadata.tags())
                .build();
        return new PendingBenefitDocument(document, searchableText);
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

    private String contentHash(String searchableText, String sourceUpdatedAt, String documentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", EMBEDDING_VERSION, documentId, sourceUpdatedAt, searchableText)
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

    private String enumName(Enum<?> value) {
        return value == null ? "" : value.name();
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
