package com.itplace.userapi.user;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserCode implements BaseCode {
    USER_INFO_SUCCESS("USER_INFO_SUCCESS", HttpStatus.OK, "사용자 정보 조회에 성공했습니다."),
    EMAIL_FIND_SUCCESS("EMAIL_FIND_SUCCESS", HttpStatus.OK, "이메일 찾기를 성공했습니다."),
    EMAIL_FIND_FAILURE("EMAIL_FIND_FAILURE", HttpStatus.BAD_REQUEST, "이메일 찾기를 실패했습니다."),
    USER_NOT_FOUND("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "사용자 정보를 찾을 수 없습니다."),
    NO_MEMBERSHIP("NO_MEMBERSHIP", HttpStatus.BAD_REQUEST, "해당 사용자는 멤버십 정보를 가지고 있지 않습니다."),
    UPLUS_DATA_EXISTS("UPLUS_DATA_EXISTS", HttpStatus.OK, "유플러스 데이터가 존재합니다."),
    UPLUS_DATA_NOT_EXISTS("UPLUS_DATA_NOT_EXISTS", HttpStatus.BAD_REQUEST, "유플러스 데이터가 존재하지 않습니다."),
    UPLUS_DATA_LINKED("UPLUS_DATA_LINKED", HttpStatus.OK, "유플러스 데이터가 연동되었습니다."),
    PASSWORD_CHANGE_SUCCESS("PASSWORD_CHANGE_SUCCESS", HttpStatus.OK, "비밀번호 변경에 성공했습니다."),
    USER_WITHDRAWAL_SUCCESS("USER_WITHDRAWAL_SUCCESS", HttpStatus.OK, "회원 탈퇴가 완료되었습니다."),
    MEMBERSHIP_NOT_FOUND("MEMBERSHIP_NOT_FOUND", HttpStatus.NOT_FOUND, "해당 멤버십 정보를 찾을 수 없습니다."),

    COUPON_COUNT_SUCCESS("COUPON_COUNT_SUCCESS", HttpStatus.OK, "사용자의 쿠폰 개수 조회에 성공했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
