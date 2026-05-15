package com.itplace.userapi.user.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.MembershipProfileUpdateRequest;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import com.itplace.userapi.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "User", description = "사용자 마이페이지 관련 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MyPageController {

    private final UserService userService;

    @PatchMapping("/changePassword")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestBody @Validated ChangePasswordRequest request) {
        userService.changePassword(principalDetails, request);
        ApiResponse<Void> body = ApiResponse.ok(UserCode.PASSWORD_CHANGE_SUCCESS);
        return body.toResponseEntity();
    }


    @PatchMapping("/membership-profile")
    public ResponseEntity<ApiResponse<UserInfoResponse>> updateMembershipProfile(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestBody @Validated MembershipProfileUpdateRequest request) {
        UserInfoResponse response = userService.updateMembershipProfile(principalDetails, request);
        ApiResponse<UserInfoResponse> body = ApiResponse.of(UserCode.MEMBERSHIP_PROFILE_UPDATE_SUCCESS, response);
        return body.toResponseEntity();
    }
}
