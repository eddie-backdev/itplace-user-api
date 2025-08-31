package com.itplace.userapi.common;

import com.itplace.userapi.common.exception.BusinessException;
import com.itplace.userapi.security.SecurityCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleIllegalStateException(Exception ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<String>> handleRuntimeException(RuntimeException ex) {
        log.info("ex: ", ex);
        ApiResponse<String> body = ApiResponse.of(SecurityCode.INTERNAL_SERVER_ERROR, ex.getMessage());
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException ex) {
        ApiResponse<Void> body = ApiResponse.of(ex.getCode(), null);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        ApiResponse<Void> body = ApiResponse.of(SecurityCode.INVALID_INPUT_VALUE, null);
        return new ResponseEntity<>(body, body.getStatus());
    }
}