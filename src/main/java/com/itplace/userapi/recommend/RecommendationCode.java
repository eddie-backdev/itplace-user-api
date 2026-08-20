package com.itplace.userapi.recommend;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecommendationCode implements BaseCode {

    RECOMMENDATION_SUCCESS("RECOMMENDATION_RESULT_SUCCESS", HttpStatus.OK, "추천 결과 생성 완료"),
    RECOMMENDATION_FAIL("RECOMMENDATION_RESULT_FAIL", HttpStatus.INTERNAL_SERVER_ERROR, "추천 결과 생성 실패"),
    RECOMMENDATION_GENERATION_BUSY(
            "RECOMMENDATION_GENERATION_BUSY",
            HttpStatus.SERVICE_UNAVAILABLE,
            "추천을 생성하고 있습니다. 잠시 후 다시 시도해 주세요."
    );


    private final String code;
    private final HttpStatus status;
    private final String message;


}
