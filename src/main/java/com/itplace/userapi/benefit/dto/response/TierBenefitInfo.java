package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TierBenefitInfo {
    private Grade grade;
    private String context;
    private Boolean isAll;
}
