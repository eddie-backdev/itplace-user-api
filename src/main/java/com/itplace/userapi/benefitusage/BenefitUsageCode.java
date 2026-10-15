package com.itplace.userapi.benefitusage;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BenefitUsageCode implements BaseCode {
    LIST_SUCCESS("BENEFIT_USAGE_LIST_SUCCESS", HttpStatus.OK, "혜택 사용 기록을 조회했습니다."),
    CREATE_SUCCESS("BENEFIT_USAGE_CREATE_SUCCESS", HttpStatus.OK, "혜택 사용 기록을 저장했습니다."),
    UPDATE_SUCCESS("BENEFIT_USAGE_UPDATE_SUCCESS", HttpStatus.OK, "혜택 사용 기록을 수정했습니다."),
    DELETE_SUCCESS("BENEFIT_USAGE_DELETE_SUCCESS", HttpStatus.OK, "혜택 사용 기록을 삭제했습니다."),
    RECORD_NOT_FOUND("BENEFIT_USAGE_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND, "사용 기록을 찾을 수 없습니다."),
    INVALID_MONTH("BENEFIT_USAGE_INVALID_MONTH", HttpStatus.BAD_REQUEST, "월은 YYYY-MM 형식이어야 합니다."),
    INVALID_DATE("BENEFIT_USAGE_INVALID_DATE", HttpStatus.BAD_REQUEST, "사용일이 올바르지 않습니다."),
    DATE_IN_FUTURE("BENEFIT_USAGE_DATE_IN_FUTURE", HttpStatus.BAD_REQUEST, "사용일은 오늘 이후일 수 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
