package com.itplace.userapi.map.dto;

import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BenefitCacheDto {
    private Long benefitId;
    private String benefitName;
    private UsageType usageType;
    private MainCategory mainCategory;
    private List<TierBenefitDto> tierBenefits;

    public BenefitCacheDto(Long benefitId, String benefitName, List<TierBenefitDto> tierBenefits) {
        this(benefitId, benefitName, null, null, tierBenefits);
    }

    public BenefitCacheDto(
            Long benefitId,
            String benefitName,
            UsageType usageType,
            MainCategory mainCategory,
            List<TierBenefitDto> tierBenefits
    ) {
        this.benefitId = benefitId;
        this.benefitName = benefitName;
        this.usageType = usageType;
        this.mainCategory = mainCategory;
        this.tierBenefits = tierBenefits;
    }

    public boolean isOfflineAvailable() {
        return usageType == UsageType.OFFLINE || usageType == UsageType.BOTH;
    }
}
