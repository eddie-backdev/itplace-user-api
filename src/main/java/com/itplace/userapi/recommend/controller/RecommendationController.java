package com.itplace.userapi.recommend.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.recommend.dto.Recommendations;
import com.itplace.userapi.recommend.enums.RecommendationCode;
import com.itplace.userapi.recommend.exception.NotMembershipUserException;
import com.itplace.userapi.recommend.service.RecommendationServiceImpl;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.user.exception.UserNotFoundException;
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

        try {
            Long userId = principalDetails.getUserId();
            List<Recommendations> result = recommendationService.recommend(userId, topK);
            return ResponseEntity.ok(ApiResponse.of(RecommendationCode.RECOMMENDATION_SUCCESS, result));

        } catch (NotMembershipUserException e) {
            return ResponseEntity
                    .status(e.getCode().getStatus())
                    .body(ApiResponse.of(e.getCode(), null));

        } catch (UserNotFoundException e) {
            return ResponseEntity
                    .status(e.getCode().getStatus())
                    .body(ApiResponse.of(e.getCode(), null));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity
                    .status(RecommendationCode.RECOMMENDATION_FAIL.getStatus())
                    .body(ApiResponse.of(RecommendationCode.RECOMMENDATION_FAIL, null));
        }
    }
}


