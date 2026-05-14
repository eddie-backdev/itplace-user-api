package com.itplace.userapi.recommend.dto;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Candidate {
    private Long partnerId;
    private Long benefitId;
    private Long policyId;
    private Long tierBenefitId;
    private String benefitName;
    private String partnerName;
    private String category;
    private String mainCategory;
    private String carrier;
    private String grade;
    private Boolean allGrade;
    private String usageType;
    private String benefitType;
    private String sourceKey;
    private String sourceUrl;
    private String description;
    private String context;
    private String candidateSource;
    private Double semanticScore;
    private Double rankScore;
    private Map<String, Double> scoreComponents;
    private String businessType;
    private java.util.List<String> useCases;
    private java.util.List<String> negativeUseCases;
    private java.util.List<String> tags;
}
