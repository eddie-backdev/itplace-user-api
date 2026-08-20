package com.itplace.userapi.recommend.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import com.itplace.userapi.recommend.RecommendationCode;
import lombok.Getter;

@Getter
public class RecommendationGenerationBusyException extends BusinessException {

    private final BaseCode code = RecommendationCode.RECOMMENDATION_GENERATION_BUSY;

    public RecommendationGenerationBusyException() {
        super(RecommendationCode.RECOMMENDATION_GENERATION_BUSY.getMessage());
    }
}
