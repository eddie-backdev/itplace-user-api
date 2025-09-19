package com.itplace.userapi.security.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class DuplicatePhoneNumberException extends BusinessException {

    private final BaseCode code;

    public DuplicatePhoneNumberException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
