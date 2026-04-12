package com.itplace.userapi.recommend.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.RecommendationCode;
import com.itplace.userapi.recommend.service.RecommendationServiceImpl;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationServiceImpl recommendationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Recommendations>>> recommend(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestParam(defaultValue = "10") int topK) {

        Long userId = principalDetails.getUserId();
        List<Recommendations> result = recommendationService.recommend(userId, topK);
        return ResponseEntity.ok(ApiResponse.of(RecommendationCode.RECOMMENDATION_SUCCESS, result));
    }
}


