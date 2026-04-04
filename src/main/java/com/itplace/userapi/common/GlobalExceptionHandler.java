package com.itplace.userapi.common;

import com.itplace.userapi.common.exception.BusinessException;
import com.itplace.userapi.security.SecurityCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        ApiResponse<Void> body = ApiResponse.of(SecurityCode.INTERNAL_SERVER_ERROR, null);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled runtime exception", ex);
        ApiResponse<Void> body = ApiResponse.of(SecurityCode.INTERNAL_SERVER_ERROR, null);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException ex) {
        ApiResponse<Void> body = ApiResponse.of(ex.getCode(), null);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<String>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        ApiResponse<String> body = ApiResponse.of(SecurityCode.INVALID_INPUT_VALUE, errors);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<String>> handleConstraintViolationException(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        ApiResponse<String> body = ApiResponse.of(SecurityCode.INVALID_INPUT_VALUE, errors);
        return new ResponseEntity<>(body, body.getStatus());
    }
}