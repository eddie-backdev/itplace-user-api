package com.itplace.userapi.map.dto;

import com.itplace.userapi.map.dto.response.TierBenefitDto;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BenefitCacheDto {
    private Long benefitId;
    private String benefitName;
    private List<TierBenefitDto> tierBenefits;
}
