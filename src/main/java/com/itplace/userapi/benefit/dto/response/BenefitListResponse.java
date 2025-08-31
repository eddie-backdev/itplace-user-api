package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitListResponse {
    // Benefit
    private Long benefitId;
    private String benefitName;
    private MainCategory mainCategory;
    private UsageType usageType;

    // Partner
    private Long partnerId;
    private String category;
    private String image;

    // TierBenefit
    private List<TierBenefitInfo> tierBenefits;

    // Favorite
    private Boolean isFavorite;
    private Long favoriteCount;

}
