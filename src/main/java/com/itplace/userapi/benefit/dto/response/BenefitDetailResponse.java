package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitDetailResponse {
    private Long benefitId;
    private String benefitName;
    private String description;
    private String benefitLimit;
    private String manual;
    private String url;
    private String sourceUrl;
    private Instant sourceCapturedAt;
    private BenefitConditions conditions;
    private Carrier carrier;
    private UsageType usageType;
    private Boolean active;

    // Partner Info
    private String partnerName;
    private String image;
    private Boolean isFavorite;
    private List<TierBenefitInfo> tierBenefits;
    private com.itplace.userapi.benefit.entity.enums.MainCategory mainCategory;
    private List<BenefitPolicyDetail> policies;
}
