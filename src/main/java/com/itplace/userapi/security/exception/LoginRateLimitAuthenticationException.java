package com.itplace.userapi.security.exception;

import org.springframework.security.core.AuthenticationException;

public class LoginRateLimitAuthenticationException extends AuthenticationException {

    public LoginRateLimitAuthenticationException(String message) {
        super(message);
    }
}
