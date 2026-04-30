package com.itplace.userapi.user.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class InvalidMembershipProfileException extends BusinessException {
    private final BaseCode code;

    public InvalidMembershipProfileException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
