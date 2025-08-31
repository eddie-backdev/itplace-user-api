package com.itplace.userapi.event.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class InvalidResultTypeException extends BusinessException {
    private final BaseCode code;

    public InvalidResultTypeException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
