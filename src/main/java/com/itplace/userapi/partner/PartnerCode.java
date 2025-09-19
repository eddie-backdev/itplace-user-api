package com.itplace.userapi.partner;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PartnerCode implements BaseCode {
    PARTNER_NOT_FOUND("PARTNER_NOT_FOUND", HttpStatus.NOT_FOUND, "제휴사를 찾을 수 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
