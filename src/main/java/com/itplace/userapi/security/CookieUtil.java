package com.itplace.userapi.security;

import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CookieUtil {

    private final JWTUtil jwtUtil;

    @Value("${app.cookie.domain}")
    private String cookieDomain;

    public void setAccessTokenCookie(HttpServletResponse response, String accessToken) {
        ResponseCookie accessTokenCookie = ResponseCookie.from(JWTConstants.CATEGORY_ACCESS, accessToken)
                .path("/")
                .secure(true)
                .sameSite("None")
                .httpOnly(true)
                .domain(cookieDomain)
                .maxAge(jwtUtil.getAccessTokenValidityInMS() / 1000)
                .build();
        response.addHeader("Set-Cookie", accessTokenCookie.toString());
    }

    public void setTokensToCookie(HttpServletResponse response, String accessToken, String refreshToken) {
        setAccessTokenCookie(response, accessToken);

        ResponseCookie refreshTokenCookie = ResponseCookie.from(JWTConstants.CATEGORY_REFRESH, refreshToken)
                .path("/")
                .secure(true)
                .sameSite("None")
                .httpOnly(true)
                .domain(cookieDomain)
                .maxAge(jwtUtil.getRefreshTokenValidityInMS() / 1000)
                .build();
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());
    }

    public void expireCookie(HttpServletResponse response, String category) {
        ResponseCookie expiredCookie = ResponseCookie.from(category, "")
                .path("/")
                .domain(cookieDomain)
                .secure(true)
                .httpOnly(true)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", expiredCookie.toString());
    }
}
