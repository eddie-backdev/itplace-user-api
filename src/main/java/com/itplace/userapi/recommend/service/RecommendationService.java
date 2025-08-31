package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.dto.Recommendations;
import java.util.List;

public interface RecommendationService {
    List<Recommendations> recommend(Long userId, int topK) throws Exception;
}
