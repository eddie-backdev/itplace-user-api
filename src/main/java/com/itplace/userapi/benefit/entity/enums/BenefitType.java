package com.itplace.userapi.benefit.entity.enums;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.Arrays;
import lombok.Getter;

@Getter
public enum BenefitType {
    FREE("증정"),
    DISCOUNT("할인");

    private final String label;

    BenefitType(String label) {
        this.label = label;
    }

    public static BenefitType fromLabel(String label) {
        return Arrays.stream(values())
                .filter(c -> c.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new InvalidEnumException(BenefitCode.BENEFIT_TYPE_NOT_FOUND));
    }
}
