package com.itplace.userapi.benefit.entity.enums;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class MainCategoryRequestConverter implements Converter<String, MainCategory> {
    @Override public MainCategory convert(String source) { return MainCategory.of(source); }
}
