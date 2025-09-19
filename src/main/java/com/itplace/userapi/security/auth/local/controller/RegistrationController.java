package com.itplace.userapi.security.auth.local.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.request.LinkLocalRequest;
import com.itplace.userapi.security.auth.local.dto.request.LoadOAuthDataRequest;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.auth.local.dto.request.UplusDataRequest;
import com.itplace.userapi.security.auth.local.dto.response.LoadOAuthDataResponse;
import com.itplace.userapi.security.auth.local.dto.response.UplusDataResponse;
import com.itplace.userapi.security.auth.local.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RegistrationController {

    private final AuthService authService;

    @PostMapping("/signUp")
    public ResponseEntity<ApiResponse<Void>> signUp(@RequestBody @Validated SignUpRequest request) {
        authService.signUp(request);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.SIGNUP_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/loadUplusData")
    public ResponseEntity<ApiResponse<UplusDataResponse>> uplusData(@RequestBody UplusDataRequest request) {
        return authService.uplusData(request)
                .map(data -> {
                    ApiResponse<UplusDataResponse> body = ApiResponse.of(
                            SecurityCode.UPLUS_DATA_FOUND, data);
                    return new ResponseEntity<>(body, body.getStatus());
                })
                .orElseGet(() -> {
                    ApiResponse<UplusDataResponse> body = ApiResponse.of(
                            SecurityCode.UPLUS_DATA_NOT_FOUND, null);
                    return new ResponseEntity<>(body, body.getStatus());
                });
    }

    @PostMapping("/loadOAuthData")
    public ResponseEntity<ApiResponse<LoadOAuthDataResponse>> loadOAuthData(@RequestBody @Validated LoadOAuthDataRequest request) {
        LoadOAuthDataResponse loadOAuthDataResponse = authService.loadOAuthData(request);
        ApiResponse<LoadOAuthDataResponse> body = ApiResponse.of(SecurityCode.LOAD_OAUTH_DATA_SUCCESS, loadOAuthDataResponse);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/link")
    public ResponseEntity<ApiResponse<Void>> link(@RequestBody @Validated LinkLocalRequest request) {
        authService.link(request);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.LINK_LOCAL_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
