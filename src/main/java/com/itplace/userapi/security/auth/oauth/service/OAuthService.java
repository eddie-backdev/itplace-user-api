package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;

public interface OAuthService {

    // 신규 OAuth 사용자 가입
    OAuthResult signUpWithOAuth(String tempToken, OAuthSignUpRequest request);

    // 기존 계정에 OAuth 계정 연동
    OAuthResult linkOAuthAccount(String tempToken, OAuthLinkRequest request);

    LoginResponse result(PrincipalDetails principalDetails);
}
