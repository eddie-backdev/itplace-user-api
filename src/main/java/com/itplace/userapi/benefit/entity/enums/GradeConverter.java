package com.itplace.userapi.benefit.entity.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class GradeConverter implements AttributeConverter<Grade, String> {
    @Override public String convertToDatabaseColumn(Grade grade) { return grade == null ? null : grade.getCode(); }
    @Override public Grade convertToEntityAttribute(String code) { return Grade.of(code); }
}
