package com.itplace.userapi.favorite.dto;

import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class TierBenefitDetail {
    private Grade grade;
    private Boolean isAll;
    private String context;
    private Integer discountValue;
}

