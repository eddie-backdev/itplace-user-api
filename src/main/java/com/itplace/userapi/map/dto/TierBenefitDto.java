package com.itplace.userapi.map.dto;

import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class TierBenefitDto {
    private Grade grade;
    private String context;
}
