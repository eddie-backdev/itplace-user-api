package com.itplace.userapi.benefit.entity.enums;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class GradeRequestConverter implements Converter<String, Grade> {
    @Override public Grade convert(String source) { return Grade.of(source); }
}
