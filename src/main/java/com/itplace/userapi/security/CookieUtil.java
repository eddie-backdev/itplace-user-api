package com.itplace.userapi.security;

import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class CookieUtil {

    private final JWTUtil jwtUtil;

    @Value("${app.cookie.domain:}")
    private String cookieDomain;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site:None}")
    private String cookieSameSite;

    public void setAccessTokenCookie(HttpServletResponse response, String accessToken) {
        ResponseCookie accessTokenCookie = baseCookie(JWTConstants.CATEGORY_ACCESS, accessToken)
                .maxAge(jwtUtil.getAccessTokenValidityInMS() / 1000)
                .build();
        response.addHeader("Set-Cookie", accessTokenCookie.toString());
    }

    public void setTokensToCookie(HttpServletResponse response, String accessToken, String refreshToken) {
        setAccessTokenCookie(response, accessToken);

        ResponseCookie refreshTokenCookie = baseCookie(JWTConstants.CATEGORY_REFRESH, refreshToken)
                .maxAge(jwtUtil.getRefreshTokenValidityInMS() / 1000)
                .build();
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());
    }

    public void setTempTokenCookie(HttpServletResponse response, String tempToken, long maxAgeSeconds) {
        ResponseCookie tempTokenCookie = baseCookie("tempToken", tempToken)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader("Set-Cookie", tempTokenCookie.toString());
    }

    public void expireCookie(HttpServletResponse response, String category) {
        ResponseCookie expiredCookie = baseCookie(category, "")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", expiredCookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .httpOnly(true);

        if (StringUtils.hasText(cookieDomain) && !"localhost".equalsIgnoreCase(cookieDomain)) {
            builder.domain(cookieDomain);
        }

        return builder;
    }
}
