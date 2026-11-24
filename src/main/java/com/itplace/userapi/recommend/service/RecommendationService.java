package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.dto.response.Recommendations;
import java.util.List;

public interface RecommendationService {
    int MAX_RECOMMENDATION_COUNT = 5;

    List<Recommendations> recommend(Long userId, int topK);
}
