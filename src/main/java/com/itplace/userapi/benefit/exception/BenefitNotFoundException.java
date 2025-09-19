package com.itplace.userapi.benefit.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class BenefitNotFoundException extends BusinessException {
    private final BaseCode code;

    public BenefitNotFoundException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
