package com.itplace.userapi.map;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreCode implements BaseCode {
    STORE_LIST_SUCCESS("STORE_LIST_SUCCESS", HttpStatus.OK, "사용자 위치 기반 매장 조회에 성공했습니다."),
    PARAMETER_CHECK("PARAMETER_CHECK", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    KEYWORD_REQUEST("KEYWORD_REQUEST", HttpStatus.BAD_REQUEST, "키워드를 입력해주세요"),

    STORE_NOT_FOUND("STORE_NOT_FOUND", HttpStatus.NOT_FOUND, "지점을 찾을 수 없습니다."),
    STORE_PARTNER_MISMATCH("STORE_PARTNER_MISMATCH", HttpStatus.BAD_REQUEST, "지점과 제휴사가 일치하지 않습니다."),

    PARTNERNAME_REQUEST("PARTNERNAME_REQUEST", HttpStatus.BAD_REQUEST, "제휴사명을 입력해주세요");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
