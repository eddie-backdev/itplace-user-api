package com.itplace.userapi.benefit.entity.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class BenefitTypeConverter implements AttributeConverter<BenefitType, String> {
    @Override
    public String convertToDatabaseColumn(BenefitType attribute) {
        return attribute != null ? attribute.getLabel() : null;
    }

    @Override
    public BenefitType convertToEntityAttribute(String dbData) {
        return dbData != null ? BenefitType.fromLabel(dbData) : null;
    }
}

