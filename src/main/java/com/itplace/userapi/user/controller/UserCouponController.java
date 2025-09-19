package com.itplace.userapi.user.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserCouponController {
    private final UserService userService;

    @GetMapping("/coupon")
    public ResponseEntity<ApiResponse<Integer>> getMyCouponCount(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        Long userId = principalDetails.getUserId();
        Integer response = userService.getUserCouponCount(userId);
        ApiResponse<Integer> body = ApiResponse.of(UserCode.COUPON_COUNT_SUCCESS, response);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
