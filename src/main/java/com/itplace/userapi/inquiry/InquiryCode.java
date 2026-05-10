package com.itplace.userapi.inquiry;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InquiryCode implements BaseCode {
    INQUIRY_CREATE_SUCCESS("INQUIRY_CREATE_SUCCESS", HttpStatus.CREATED, "문의가 접수되었습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
