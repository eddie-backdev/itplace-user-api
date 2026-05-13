package com.itplace.userapi.ai.rag.document;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BenefitDocument {
    private String id;
    private String documentId;
    private List<Float> embedding;
    private String partnerId;
    private String partnerName;
    private String benefitId;
    private String benefitName;
    private String policyId;
    private String tierBenefitId;
    private String carrierBenefitName;
    private String category;
    private String mainCategory;
    private String carrier;
    private String grade;
    private Boolean isAllGrade;
    private String usageType;
    private String benefitType;
    private Boolean active;
    private String sourceKey;
    private String sourceUrl;
    private String sourceCategory;
    private String lastCrawledAt;
    private String indexedAt;
    private String sourceUpdatedAt;
    private String embeddingVersion;
    private String contentHash;
    private String syncStatus;
    private String deletedAt;
    private String description;
    private String manual;
    private String context;
    private String tierContext;
    private Integer discountValue;
}
