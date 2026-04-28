package com.itplace.userapi.security;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SecurityCode implements BaseCode {

    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "예기치 못한 오류가 발생했습니다"),
    USER_NOT_FOUND("USER_NOT_FOUND", HttpStatus.BAD_REQUEST, "사용자를 찾을 수 없습니다"),

    // 로그인
    LOGIN_SUCCESS("LOGIN_SUCCESS", HttpStatus.OK, "성공적으로 로그인 되었습니다."),
    LOGIN_FAIL("LOGIN_FAIL", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
    LOGOUT_SUCCESS("LOGOUT_SUCCESS", HttpStatus.OK, "성공적으로 로그아웃 되었습니다."),
    UNAUTHORIZED_ACCESS("UNAUTHORIZED_ACCESS", HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다."),

    // 회원가입
    PASSWORD_MISMATCH("PASSWORD_MISMATCH", HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    SIGNUP_SUCCESS("SIGNUP_SUCCESS", HttpStatus.OK, "성공적으로 회원가입 되었습니다."),
    INVALID_INPUT_VALUE("INVALID_INPUT_VALUE", HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    // 이메일
    EMAIL_CODE_EXPIRED("EMAIL_CODE_EXPIRED", HttpStatus.BAD_REQUEST, "이메일 인증 코드가 만료되었습니다."),
    EMAIL_CODE_MISMATCH("EMAIL_CODE_MISMATCH", HttpStatus.BAD_REQUEST, "이메일 인증 코드가 일치하지 않습니다."),
    EMAIL_SEND_SUCCESS("EMAIL_SEND_SUCCESS", HttpStatus.OK, "이메일 인증 코드 발송에 성공했습니다."),
    EMAIL_SEND_FAILURE("EMAIL_SEND_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
    DUPLICATE_EMAIL("DUPLICATE_EMAIL", HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    EMAIL_VERIFICATION_SUCCESS("EMAIL_VERIFICATION_SUCCESS", HttpStatus.OK, "이메일 인증에 성공했습니다."),
    EMAIL_VERIFICATION_FAILURE("EMAIL_VERIFICATION_FAILURE", HttpStatus.BAD_REQUEST, "이메일 인증에 실패했습니다."),


    // 비밀번호 변경
    RESET_PASSWORD_SUCCESS("RESET_PASSWORD_SUCCESS", HttpStatus.OK, "비밀번호가 변경되었습니다."),
    RESET_PASSWORD_FAILURE("RESET_PASSWORD_FAILURE", HttpStatus.BAD_REQUEST, "비밀번호 변경을 실패했습니다."),

    // 토큰
    RENEW_ACCESS_TOKEN("RENEW_ACCESS_TOKEN", HttpStatus.OK, "액세스 토큰이 갱신되었습니다."),
    INVALID_TOKEN("INVALID_TOKEN", HttpStatus.UNAUTHORIZED, "잘못된 토큰입니다."),
    REFRESH_TOKEN_REQUIRE("REFRESH_TOKEN_REQUIRE", HttpStatus.UNAUTHORIZED, "리프레시 토큰이 필요합니다."),
    REFRESH_TOKEN_EXPIRED("REFRESH_TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었습니다."),
    INVALID_TOKEN_TYPE("INVALID_TOKEN_TYPE", HttpStatus.UNAUTHORIZED, "잘못된 토큰 타입입니다."),

    // OAuth2, 추가 정보 입력 전 임시 인증 성공
    OAUTH_INFO_FOUND("OAUTH_INFO_FOUND", HttpStatus.OK, "OAuth 유저 정보를 찾았습니다."),
    PRE_AUTHENTICATION_SUCCESS("PRE_AUTHENTICATION_SUCCESS", HttpStatus.OK, "임시 인증에 성공했습니다. 추가 정보 입력이 필요합니다."),
    // 회원가입
    INVALID_REGISTRATION_SESSION("INVALID_REGISTRATION_SESSION", HttpStatus.BAD_REQUEST, "잘못된 가입 요청입니다."),

    // Recaptcha
    RECAPTCHA_SUCCESS("RECAPTCHA_SUCCESS", HttpStatus.OK, "Recaptcha 인증에 성공했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;

    public static BaseCode fromCode(String code) {
        for (SecurityCode securityCode : SecurityCode.values()) {
            if (securityCode.getCode().equals(code)) {
                return securityCode;
            }
        }
        throw new IllegalArgumentException("No matching SecurityCode for code: " + code);
    }
}
