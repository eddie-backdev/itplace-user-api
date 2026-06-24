package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TierBenefitInfo {
    private Carrier carrier;
    private Grade grade;
    private String context;
    private String onlineContext;
    private String offlineContext;
    private Boolean isAll;

    public TierBenefitInfo(Carrier carrier, Grade grade, String context, Boolean isAll) {
        this(carrier, grade, context, null, null, isAll);
    }
}
