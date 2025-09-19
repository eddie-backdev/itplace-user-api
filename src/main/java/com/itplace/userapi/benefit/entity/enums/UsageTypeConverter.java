package com.itplace.userapi.benefit.entity.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class UsageTypeConverter implements AttributeConverter<UsageType, String> {
    @Override
    public String convertToDatabaseColumn(UsageType attribute) {
        return attribute != null ? attribute.getLabel() : null;
    }

    @Override
    public UsageType convertToEntityAttribute(String dbData) {
        return dbData != null ? UsageType.fromLabel(dbData) : null;
    }
}