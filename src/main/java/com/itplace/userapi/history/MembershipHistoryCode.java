package com.itplace.userapi.history;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MembershipHistoryCode implements BaseCode {
    MEMBERSHIP_HISTORY_SUCCESS("MEMBERSHIP_HISTORY_SUCCESS", HttpStatus.OK, "멤버십 사용 내역 조회에 성공했습니다."),
    MEMBERSHIP_HISTORY_SUMMARY_SUCCESS("MEMBERSHIP_HISTORY_SUMMARY_SUCCESS", HttpStatus.OK, "이번 달 할인 금액 조회에 성공했습니다."),
    UNAUTHORIZED_MEMBERSHIP_ACCESS("UNAUTHORIZED_MEMBERSHIP_ACCESS", HttpStatus.UNAUTHORIZED,
            "멤버십 내역 조회는 로그인 후 이용 가능합니다."),
    ALREADY_USED_THIS_MONTH("ALREADY_USED_THIS_MONTH", HttpStatus.BAD_REQUEST, "이번 달에 이미 사용한 혜택입니다."),
    ALREADY_USED_TODAY("ALREADY_USED_TODAY", HttpStatus.BAD_REQUEST, "오늘 이미 사용한 혜택입니다."),
    ALREADY_USED_ONCE("ALREADY_USED_ONCE", HttpStatus.BAD_REQUEST, "해당 혜택은 최초 1회만 사용할 수 있습니다."),
    MEMBERSHIP_USE_SUCCESS("MEMBERSHIP_USE_SUCCESS", HttpStatus.OK, "멤버십 혜택 사용에 성공했습니다."),
    AMOUNT_REQUIRED("AMOUNT_REQUIRED", HttpStatus.BAD_REQUEST, "할인 금액 계산을 위해 유효한 금액을 입력해야 합니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}

