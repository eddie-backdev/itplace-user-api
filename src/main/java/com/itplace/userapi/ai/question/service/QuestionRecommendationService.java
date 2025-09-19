package com.itplace.userapi.ai.question.service;

import com.itplace.userapi.ai.llm.dto.RecommendationResponse;

public interface QuestionRecommendationService {
    RecommendationResponse recommendByQuestion(String question, double lat, double lng) throws Exception;
}