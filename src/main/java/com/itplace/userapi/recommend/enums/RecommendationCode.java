package com.itplace.userapi.recommend.enums;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecommendationCode implements BaseCode {

    RECOMMENDATION_SUCCESS("RECOMMENDATION_RESULT_SUCCESS", HttpStatus.OK, "추천 결과 생성 완료"),
    RECOMMENDATION_FAIL("RECOMMENDATION_RESULT_FAIL", HttpStatus.INTERNAL_SERVER_ERROR, "추천 결과 생성 실패"),
    USER_NOT_MEMBERSHIP("RECOMMENDATION_USER_NOT_MEMBERSHIP", HttpStatus.BAD_REQUEST, "멤버십 회원이 아닙니다.");


    private final String code;
    private final HttpStatus status;
    private final String message;


}

