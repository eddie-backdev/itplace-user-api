package com.itplace.userapi.security.auth.oauth.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.request.KakaoCodeRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoLoginResult;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthPreAuthResponse;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.auth.oauth.service.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
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
@io.swagger.v3.oas.annotations.tags.Tag(name = "Auth", description = "사용자 인증 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {

    private final OAuthService oAuthService;
    private final CookieUtil cookieUtil;

    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<?>> kakaoLogin(
            @RequestBody @Validated KakaoCodeRequest request,
            HttpServletResponse httpServletResponse
    ) {
        KakaoLoginResult result = oAuthService.processKakaoLogin(request);
        if (result.isExistingUser()) {
            OAuthResult authResult = result.getAuthResult();
            cookieUtil.setTokensToCookie(httpServletResponse, authResult.getAccessToken(), authResult.getRefreshToken());
            ApiResponse<LoginResponse> body = ApiResponse.of(SecurityCode.LOGIN_SUCCESS, authResult.getLoginResponse());
            return ResponseEntity.status(body.getStatus()).body(body);
        }

        cookieUtil.setTempTokenCookie(httpServletResponse, result.getTempToken(), TimeUnit.MINUTES.toSeconds(10));
        OAuthPreAuthResponse preAuthResponse = OAuthPreAuthResponse.builder()
                .email(result.getEmail())
                .nickname(result.getNickname())
                .build();
        ApiResponse<OAuthPreAuthResponse> body = ApiResponse.of(SecurityCode.PRE_AUTHENTICATION_SUCCESS, preAuthResponse);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /**
     * 신규 사용자가 카카오 검증 이메일과 추가 정보를 조합해 최종 가입할 때 호출됩니다.
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
        return body.toResponseEntity();
    }

    /**
     * 기존 사용자가 이메일 기준으로 자신의 계정에 소셜 계정을 연동할 때 호출됩니다.
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
        return body.toResponseEntity();
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<LoginResponse>> result(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        LoginResponse result = oAuthService.result(principalDetails);
        ApiResponse<LoginResponse> body = ApiResponse.of(SecurityCode.OAUTH_INFO_FOUND, result);
        return body.toResponseEntity();
    }
}
