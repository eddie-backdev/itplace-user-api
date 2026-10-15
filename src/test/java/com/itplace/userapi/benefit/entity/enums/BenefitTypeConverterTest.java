package com.itplace.userapi.benefit.entity.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenefitTypeConverterTest {
    private final BenefitTypeConverter converter = new BenefitTypeConverter();

    @Test
    void readsAndRoundTripsEveryPersistedBenefitLabel() {
        Map<String, BenefitType> persistedLabels = Map.of(
                "증정", BenefitType.FREE,
                "할인", BenefitType.DISCOUNT,
                "적립", BenefitType.POINT_ACCRUAL,
                "포인트 사용", BenefitType.POINT_USE,
                "복합", BenefitType.MIXED,
                "미확인", BenefitType.UNKNOWN);

        persistedLabels.forEach((label, type) -> {
            assertThat(converter.convertToEntityAttribute(label)).isEqualTo(type);
            assertThat(converter.convertToDatabaseColumn(type)).isEqualTo(label);
        });
    }

    @Test
    void rejectsUnknownPersistedLabels() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("unexpected"))
                .isInstanceOfSatisfying(InvalidEnumException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(BenefitCode.BENEFIT_TYPE_NOT_FOUND));
    }
}
