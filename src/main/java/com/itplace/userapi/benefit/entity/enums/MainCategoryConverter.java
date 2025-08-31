package com.itplace.userapi.benefit.entity.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class MainCategoryConverter implements AttributeConverter<MainCategory, String> {
    @Override
    public String convertToDatabaseColumn(MainCategory attribute) {
        return attribute != null ? attribute.getLabel() : null;
    }

    @Override
    public MainCategory convertToEntityAttribute(String dbData) {
        return dbData != null ? MainCategory.fromLabel(dbData) : null;
    }
}
