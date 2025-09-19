package com.itplace.userapi.benefit;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BenefitCode implements BaseCode {
    BENEFIT_LIST_SUCCESS("BENEFIT_LIST_SUCCESS", HttpStatus.OK, "혜택 목록 조회에 성공했습니다."),
    BENEFIT_DETAIL_SUCCESS("BENEFIT_DETAIL_SUCCESS", HttpStatus.OK, "혜택 상세 조회에 성공했습니다."),
    BENEFIT_NOT_FOUND("BENEFIT_NOT_FOUND", HttpStatus.NOT_FOUND, "존재하지 않는 혜택입니다."),

    BENEFIT_TYPE_NOT_FOUND("BENEFIT_TYPE_NOT_FOUND", HttpStatus.BAD_REQUEST, "존재하지 않는 benefit type입니다."),
    MAIN_CATEGORY_NOT_FOUND("MAIN_CATEGORY_NOT_FOUND", HttpStatus.BAD_REQUEST, "존재하지 않는 main category입니다."),
    USAGE_TYPE_NOT_FOUND("USAGE_TYPE_NOT_FOUND", HttpStatus.BAD_REQUEST, "존재하지 않는 usage type입니다."),

    BENEFIT_OFFLINE_NOT_FOUND("BENEFIT_OFFLINE_NOT_FOUND", HttpStatus.OK, "오프라인 사용 가능한 혜택이 존재하지 않습니다."),
    INVALID_GRADE_FOR_BENEFIT("INVALID_GRADE_FOR_BENEFIT", HttpStatus.BAD_REQUEST, "해당 등급은 이 혜택을 사용할 수 없습니다."),
    TIER_BENEFIT_NOT_FOUND("TIER_BENEFIT_NOT_FOUND", HttpStatus.BAD_REQUEST, "해당 등급은 이 혜택을 사용할 수 없습니다."),
    INVALID_BENEFIT_TYPE("INVALID_BENEFIT_TYPE", HttpStatus.BAD_REQUEST, "잘못된 혜택 타입입니다."),

    BENEFIT_DETAIL_NOT_FOUND("BENEFIT_DETAIL_NOT_FOUND", HttpStatus.OK, "해당하는 혜택이 존재하지 않습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
