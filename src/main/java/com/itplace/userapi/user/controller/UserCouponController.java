package com.itplace.userapi.user.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.user.UserCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "User", description = "사용자 마이페이지 관련 API")
@RestController
@RequestMapping("/api/v1/users")
public class UserCouponController {

    @GetMapping("/coupon")
    public ResponseEntity<ApiResponse<Integer>> getMyCouponCount() {
        ApiResponse<Integer> body = ApiResponse.of(UserCode.COUPON_EVENT_DISABLED, 0);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
