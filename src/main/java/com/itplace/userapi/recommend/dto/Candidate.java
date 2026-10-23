package com.itplace.userapi.recommend.dto;

import java.util.Map;
import java.time.Instant;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class Candidate {
    private Long partnerId;
    private Long benefitId;
    private Long policyId;
    private Long tierBenefitId;
    private String candidateKey;
    private String offerKey;
    private String benefitName;
    private String partnerName;
    private String imgUrl;
    private String category;
    private String mainCategory;
    private String carrier;
    private String grade;
    private Boolean allGrade;
    private String usageType;
    private String benefitType;
    private String sourceKey;
    private String sourceUrl;
    private Instant sourceCapturedAt;
    private String sourceLimitText;
    private BenefitConditions conditions;
    private String membershipOptionType;
    private String sourceOptionLabel;
    private String description;
    private String manual;
    private String context;
    private String onlineContext;
    private String offlineContext;
    private String candidateSource;
    private Double semanticScore;
    private Double rankScore;
    private Map<String, Double> scoreComponents;
    private String businessType;
    private java.util.List<String> useCases;
    private java.util.List<String> negativeUseCases;
    private java.util.List<String> tags;
}
