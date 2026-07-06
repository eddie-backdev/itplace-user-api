package com.itplace.userapi.ai.question.service;

import com.itplace.userapi.ai.llm.dto.response.RecommendationResponse;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;

public interface QuestionRecommendationService {
    RecommendationResponse recommendByQuestion(String question, double lat, double lng);

    RecommendationResponse recommendByQuestion(String question, double lat, double lng, Carrier carrier, Grade grade);

    RecommendationResponse recommendByQuestion(String question, double lat, double lng, Carrier carrier, Grade grade,
                                               Carrier fallbackCarrier, Grade fallbackGrade);
}
