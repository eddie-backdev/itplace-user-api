package com.itplace.userapi.ai.forbiddenword;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ForbiddenWordCode implements BaseCode {
    FORBIDDEN_WORD_SUCCESS("FORBIDDEN_WORD_SUCCESS", HttpStatus.OK, "금칙어 필터링에 성공했습니다."),
    FORBIDDEN_WORD_DETECTED("FORBIDDEN_WORD_DETECTED", HttpStatus.BAD_REQUEST, "금칙어가 포함된 질문입니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
