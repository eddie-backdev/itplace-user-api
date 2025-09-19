package com.itplace.userapi.security.auth.oauth.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.auth.oauth.service.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {

    private final OAuthService oAuthService;
    private final CookieUtil cookieUtil;

    /**
     * 신규 사용자가 휴대폰 인증 후, 추가 정보를 입력하여 최종 가입할 때 호출됩니다.
     */
    @PostMapping("/signUp")
    public ResponseEntity<ApiResponse<LoginResponse>> oauthSignUpNew(
            @CookieValue("tempToken") String tempToken,
            @RequestBody @Validated OAuthSignUpRequest request,
            HttpServletResponse httpServletResponse
    ) {
        OAuthResult result = oAuthService.signUpWithOAuth(tempToken, request);
        cookieUtil.setTokensToCookie(httpServletResponse, result.getAccessToken(), result.getRefreshToken());
        ApiResponse<LoginResponse> body = ApiResponse.of(SecurityCode.LOGIN_SUCCESS, result.getLoginResponse());
        return new ResponseEntity<>(body, body.getStatus());
    }

    /**
     * 기존 사용자가 휴대폰 인증 후, 자신의 계정에 소셜 계정을 연동할 때 호출됩니다.
     */
    @PostMapping("/link")
    public ResponseEntity<ApiResponse<LoginResponse>> oauthSignUpLink(
            @CookieValue("tempToken") String tempToken,
            @RequestBody @Validated OAuthLinkRequest request,
            HttpServletResponse httpServletResponse
    ) {
        OAuthResult result = oAuthService.linkOAuthAccount(tempToken, request);
        cookieUtil.setTokensToCookie(httpServletResponse, result.getAccessToken(), result.getRefreshToken());
        ApiResponse<LoginResponse> body = ApiResponse.of(SecurityCode.LOGIN_SUCCESS, result.getLoginResponse());
        return new ResponseEntity<>(body, body.getStatus());
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<LoginResponse>> result(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        LoginResponse result = oAuthService.result(principalDetails);
        ApiResponse<LoginResponse> body = ApiResponse.of(SecurityCode.OAUTH_INFO_FOUND, result);
        return new ResponseEntity<>(body, body.getStatus());
    }
}