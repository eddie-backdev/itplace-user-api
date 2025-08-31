package com.itplace.userapi.benefit.entity.enums;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.Arrays;
import lombok.Getter;

@Getter
public enum UsageType {
    OFFLINE("offline"),
    ONLINE("online"),
    BOTH("both");

    private final String label;

    UsageType(String label) {
        this.label = label;
    }

    public static UsageType fromLabel(String label) {
        return Arrays.stream(values())
                .filter(c -> c.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new InvalidEnumException(BenefitCode.USAGE_TYPE_NOT_FOUND));
    }
}
