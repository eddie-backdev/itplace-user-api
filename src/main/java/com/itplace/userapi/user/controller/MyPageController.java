package com.itplace.userapi.user.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.response.CheckUplusDataResponse;
import com.itplace.userapi.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return new ResponseEntity<>(body, body.getStatus());
    }

    @GetMapping("/checkUplusData")
    public ResponseEntity<ApiResponse<CheckUplusDataResponse>> checkUplusData(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        CheckUplusDataResponse checkUplusDataResponse = userService.checkUplusData(principalDetails);
        ApiResponse<CheckUplusDataResponse> body;
        if (checkUplusDataResponse.isUplusDataExists()) {
            body = ApiResponse.ok(UserCode.UPLUS_DATA_EXISTS);
        } else {
            body = ApiResponse.ok(UserCode.UPLUS_DATA_NOT_EXISTS);
        }
        return new ResponseEntity<>(body, body.getStatus());
    }

    @GetMapping("/linkUplusData")
    public ResponseEntity<ApiResponse<Void>> linkUplusData(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        userService.linkUplusData(principalDetails);
        ApiResponse<Void> body = ApiResponse.ok(UserCode.UPLUS_DATA_LINKED);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
