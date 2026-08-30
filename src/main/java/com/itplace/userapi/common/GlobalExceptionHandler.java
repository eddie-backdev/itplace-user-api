package com.itplace.userapi.common;

import com.itplace.userapi.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ApiResponse<Void> body = ApiResponse.of(ex.getCode(), null);
        return body.toResponseEntity();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<String>> handleConstraintViolationException(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        ApiResponse<String> body = ApiResponse.of(CommonCode.INVALID_REQUEST_PARAMETER, errors);
        return body.toResponseEntity();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception", ex);
        ApiResponse<Void> body = ApiResponse.of(CommonCode.INTERNAL_SERVER_ERROR, null);
        return body.toResponseEntity();
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
        WebRequest request
    ) {
        BaseCode code = resolveCode(ex, statusCode);
        Object details = resolveDetails(ex, statusCode);

        if (statusCode.is5xxServerError()) {
            log.error("Unhandled MVC exception", ex);
        } else {
            log.debug("Request rejected: type={}, status={}, reason={}",
                    ex.getClass().getSimpleName(), statusCode.value(), ex.getMessage());
        }

        ApiResponse<Object> response = ApiResponse.of(code, details);
        return super.handleExceptionInternal(ex, response, headers, statusCode, request);
    }

    private BaseCode resolveCode(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MethodArgumentNotValidException) {
            return CommonCode.INVALID_INPUT_VALUE;
        }
        if (ex instanceof HandlerMethodValidationException
                || ex instanceof TypeMismatchException
                || ex instanceof MissingServletRequestParameterException
                || ex instanceof ServletRequestBindingException) {
            return CommonCode.INVALID_REQUEST_PARAMETER;
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return CommonCode.INVALID_REQUEST_BODY;
        }
        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
            return CommonCode.RESOURCE_NOT_FOUND;
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return CommonCode.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return CommonCode.UNSUPPORTED_MEDIA_TYPE;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return CommonCode.NOT_ACCEPTABLE;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return CommonCode.INTERNAL_SERVER_ERROR;
        }
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return CommonCode.INTERNAL_SERVER_ERROR;
        }
        return new HttpErrorCode(status.name(), status, defaultMessage(status));
    }

    private Object resolveDetails(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MethodArgumentNotValidException validationException) {
            return validationException.getBindingResult().getFieldErrors().stream()
                    .map(this::formatFieldError)
                    .collect(Collectors.joining(", "));
        }
        if (ex instanceof HandlerMethodValidationException validationException) {
            return validationException.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> parameterName(result.getMethodParameter().getParameterName())
                                    + ": " + Objects.toString(error.getDefaultMessage(), "입력값이 올바르지 않습니다.")))
                    .collect(Collectors.joining(", "));
        }
        if (statusCode.is4xxClientError() && ex instanceof ResponseStatusException responseStatusException) {
            return responseStatusException.getReason();
        }
        if (statusCode.is4xxClientError()
                && ex instanceof ErrorResponseException errorResponseException
                && errorResponseException.getBody().getDetail() != null) {
            return errorResponseException.getBody().getDetail();
        }
        return null;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + Objects.toString(error.getDefaultMessage(), "입력값이 올바르지 않습니다.");
    }

    private String parameterName(String parameterName) {
        return parameterName == null ? "parameter" : parameterName;
    }

    private String defaultMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "요청이 올바르지 않습니다.";
            case UNAUTHORIZED -> "인증이 필요합니다.";
            case FORBIDDEN -> "요청 권한이 없습니다.";
            case NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다.";
            case CONFLICT -> "요청이 현재 상태와 충돌합니다.";
            case TOO_MANY_REQUESTS -> "요청이 너무 많습니다.";
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> "외부 서비스가 일시적으로 응답하지 않습니다.";
            default -> "요청을 처리할 수 없습니다.";
        };
    }

    private record HttpErrorCode(String code, HttpStatus status, String message) implements BaseCode {
        @Override
        public String getCode() {
            return code;
        }

        @Override
        public HttpStatus getStatus() {
            return status;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
