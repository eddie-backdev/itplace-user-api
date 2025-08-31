package com.itplace.userapi.ai.question;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum QuestionCode implements BaseCode {
    QUESTION_SUCCESS("QUESTION_SUCCESS", HttpStatus.OK, "정보를 성공적으로 조회했습니다."),
    NO_CATEGORY_FOUND("NO_CATEGORY_FOUND", HttpStatus.BAD_REQUEST, "카테고리를 찾을 수 없습니다."),
    NO_STORE_FOUND("NO_STORE_FOUND", HttpStatus.NOT_FOUND, "제공할 제휴처가 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
