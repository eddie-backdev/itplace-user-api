package com.itplace.userapi.user.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class MembershipNotFoundException extends BusinessException {
    private final BaseCode code;

    public MembershipNotFoundException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
