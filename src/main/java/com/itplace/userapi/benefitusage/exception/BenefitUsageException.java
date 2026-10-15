package com.itplace.userapi.benefitusage.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class BenefitUsageException extends BusinessException {
    private final BaseCode code;

    public BenefitUsageException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
