package com.itplace.userapi.user;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserCode implements BaseCode {
    USER_INFO_SUCCESS("USER_INFO_SUCCESS", HttpStatus.OK, "사용자 정보 조회에 성공했습니다."),
    USER_NOT_FOUND("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "사용자 정보를 찾을 수 없습니다."),
    PASSWORD_CHANGE_SUCCESS("PASSWORD_CHANGE_SUCCESS", HttpStatus.OK, "비밀번호 변경에 성공했습니다."),
    MEMBERSHIP_PROFILE_UPDATE_SUCCESS("MEMBERSHIP_PROFILE_UPDATE_SUCCESS", HttpStatus.OK, "멤버십 프로필 변경에 성공했습니다."),
    INVALID_MEMBERSHIP_PROFILE("INVALID_MEMBERSHIP_PROFILE", HttpStatus.BAD_REQUEST, "통신사와 멤버십 등급 조합이 올바르지 않습니다."),
    USER_WITHDRAWAL_SUCCESS("USER_WITHDRAWAL_SUCCESS", HttpStatus.OK, "회원 탈퇴가 완료되었습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
