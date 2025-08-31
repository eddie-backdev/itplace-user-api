package com.itplace.userapi.recommend.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class NotMembershipUserException extends BusinessException {
    private final BaseCode code;

    public NotMembershipUserException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }

}
