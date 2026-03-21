package com.itplace.userapi.map.dto.response;

import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierBenefitDto {
    private Grade grade;
    private String context;
}
