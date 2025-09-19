package com.itplace.userapi.security.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class EmailVerificationException extends BusinessException {

    private final BaseCode code;

    public EmailVerificationException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
