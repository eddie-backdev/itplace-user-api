package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.security.auth.oauth.dto.response.KakaoUserProfile;

public interface KakaoOAuthProviderClient {
    KakaoUserProfile fetchUser(String authorizationCode, String redirectUri);
}
