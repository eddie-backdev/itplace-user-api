package com.itplace.userapi.benefit.entity.enums;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.Arrays;
import lombok.Getter;

@Getter
public enum MainCategory {
    VIP_COCK("VIP 콕"),
    BASIC_BENEFIT("기본 혜택");

    private final String label;

    MainCategory(String label) {
        this.label = label;
    }

    public static MainCategory fromLabel(String label) {
        return Arrays.stream(values())
                .filter(c -> c.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new InvalidEnumException(BenefitCode.MAIN_CATEGORY_NOT_FOUND));
    }
}
