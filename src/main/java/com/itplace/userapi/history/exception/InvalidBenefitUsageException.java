package com.itplace.userapi.history.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class InvalidBenefitUsageException extends BusinessException {
    private final BaseCode code;

    public InvalidBenefitUsageException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
