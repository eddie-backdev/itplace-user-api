package com.itplace.userapi.common;

import org.springframework.http.HttpStatus;

public interface BaseCode {
    String getCode();

    HttpStatus getStatus();

    String getMessage();
}
