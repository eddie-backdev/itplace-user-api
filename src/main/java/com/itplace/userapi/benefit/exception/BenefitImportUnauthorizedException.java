package com.itplace.userapi.benefit.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class BenefitImportUnauthorizedException extends BusinessException {
    private final BaseCode code;

    public BenefitImportUnauthorizedException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
