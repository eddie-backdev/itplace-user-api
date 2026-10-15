package com.itplace.userapi.benefit.entity.enums;

public enum MembershipOptionType {
    DISCOUNT,
    POINT_ACCRUAL,
    NOT_APPLICABLE,
    UNKNOWN;

    public String getDisplayLabel() {
        return switch (this) {
            case DISCOUNT -> "할인형";
            case POINT_ACCRUAL -> "적립형";
            case NOT_APPLICABLE, UNKNOWN -> null;
        };
    }
}
