package com.itplace.userapi.security.auth.oauth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.auth.oauth.dto.CustomOAuth2User;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JWTUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String NEW_USER_REDIRECT_URI = "https://itplace.click/login?step=phoneAuth&verifiedType=oauth";
    private static final String EXIST_USER_REDIRECT_URI = "https://itplace.click/login?oauth=processing";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();

        if (oAuth2User.isNewUser()) {
            // Case 1: 신규 사용자 -> 임시 토큰 발급 및 휴대폰 인증 페이지로 리다이렉트
            log.info("신규 OAuth 사용자. 추가 정보 입력 페이지로 리다이렉트합니다.");
            String tempToken = jwtUtil.createTempJwt(oAuth2User.getProvider(), oAuth2User.getProviderId());

            ResponseCookie tempTokenCookie = ResponseCookie.from("tempToken", tempToken)
                    .path("/")
                    .secure(true)
                    .sameSite("None")
                    .httpOnly(true)
                    .domain("itplace.click")
                    .maxAge(TimeUnit.MINUTES.toSeconds(10))
                    .build();
            response.addHeader("Set-Cookie", tempTokenCookie.toString());

            // 프론트엔드의 휴대폰 인증 및 추가 정보 입력 페이지로 리다이렉트
            getRedirectStrategy().sendRedirect(request, response, NEW_USER_REDIRECT_URI);
        } else {
            // Case 2: 기존 사용자 -> 즉시 로그인 성공 처리 (JWT 발급)
            log.info("기존 OAuth 사용자. 로그인을 완료합니다.");
            User user = oAuth2User.getUser();
            String role = user.getRole().getKey();
            String accessToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_ACCESS);
            String refreshToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_REFRESH);

            // Redis에 Refresh Token 저장
            redisTemplate.opsForValue().set("RT:" + user.getId(), refreshToken, jwtUtil.getRefreshTokenValidityInMS(), TimeUnit.MILLISECONDS);

            // 쿠키에 토큰 설정
            cookieUtil.setTokensToCookie(response, accessToken, refreshToken);

            // ApiResponse JSON 형식으로 응답
            getRedirectStrategy().sendRedirect(request, response, EXIST_USER_REDIRECT_URI);
        }
    }
}