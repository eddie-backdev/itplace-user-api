package com.itplace.userapi.history.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class UnauthorizedAccessException extends BusinessException {
    private final BaseCode code;

    public UnauthorizedAccessException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
