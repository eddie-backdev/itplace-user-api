package com.itplace.userapi.security.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class PasswordMismatchException extends BusinessException {

    private final BaseCode code;

    public PasswordMismatchException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
