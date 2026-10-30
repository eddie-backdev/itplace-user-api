package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record BenefitPolicyDetail(
        Long benefitId,
        Carrier carrier,
        String benefitName,
        MainCategory mainCategory,
        String description,
        BenefitConditions conditions,
        String benefitLimit,
        String manual,
        String url,
        String sourceUrl,
        Instant sourceCapturedAt,
        UsageType usageType,
        Boolean active,
        String partnerName,
        String image,
        List<TierBenefitInfo> tierBenefits,
        boolean isFavorite
) {}
