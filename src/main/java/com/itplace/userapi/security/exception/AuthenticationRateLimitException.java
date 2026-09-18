package com.itplace.userapi.security.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class AuthenticationRateLimitException extends BusinessException {

    private final BaseCode code;
    private final Long retryAfterSeconds;

    public AuthenticationRateLimitException(BaseCode code) {
        this(code, null);
    }

    public AuthenticationRateLimitException(BaseCode code, Long retryAfterSeconds) {
        super(code.getMessage());
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
