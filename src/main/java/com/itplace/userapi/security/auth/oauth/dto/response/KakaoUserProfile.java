package com.itplace.userapi.security.auth.oauth.dto.response;

import org.springframework.util.StringUtils;

public record KakaoUserProfile(
        String providerId,
        String email,
        String nickname,
        Boolean emailValid,
        Boolean emailVerified
) {
    public boolean hasVerifiedEmail() {
        return StringUtils.hasText(email) && Boolean.TRUE.equals(emailValid) && Boolean.TRUE.equals(emailVerified);
    }
}
