package com.itplace.userapi.security.auth.local.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Slf4j
@RequiredArgsConstructor
public class LoginFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;
    private final JWTUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CookieUtil cookieUtil;

    public static final String REFRESH_TOKEN_PREFIX = "RT:";

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        try {
            Map<String, String> requestBody = objectMapper.readValue(request.getInputStream(), Map.class);
            String username = requestBody.get("email");
            String password = requestBody.get("password");

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, password,
                    null);

            return authenticationManager.authenticate(authToken);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                            Authentication authResult)
            throws IOException, ServletException {
        log.info("로그인 성공");
        CustomUserDetails customUserDetails = (CustomUserDetails) authResult.getPrincipal();

        Long userId = customUserDetails.getUserId();
        Collection<? extends GrantedAuthority> authorities = authResult.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
        GrantedAuthority auth = iterator.next();
        String role = auth.getAuthority();

        LoginResponse loginResponse = getLoginResponse(customUserDetails);

        String accessToken = jwtUtil.createJwt(userId, role, JWTConstants.CATEGORY_ACCESS);
        String refreshToken = jwtUtil.createJwt(userId, role, JWTConstants.CATEGORY_REFRESH);

        String key = REFRESH_TOKEN_PREFIX + userId;
        Long refreshTokenValidityInMS = jwtUtil.getRefreshTokenValidityInMS();
        redisTemplate.opsForValue().set(key, refreshToken, refreshTokenValidityInMS, TimeUnit.MILLISECONDS);

        cookieUtil.setTokensToCookie(response, accessToken, refreshToken);

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpStatus.OK.value());
        ApiResponse<LoginResponse> apiResponse = ApiResponse.of(SecurityCode.LOGIN_SUCCESS, loginResponse);
        objectMapper.writeValue(response.getOutputStream(), apiResponse);
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                              AuthenticationException failed) throws IOException, ServletException {
        log.info("로그인 실패");
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        ApiResponse<Void> apiResponse = ApiResponse.of(SecurityCode.LOGIN_FAIL, null);
        objectMapper.writeValue(response.getOutputStream(), apiResponse);
    }

    private LoginResponse getLoginResponse(CustomUserDetails customUserDetails) {
        var user = customUserDetails.getUser();
        return LoginResponse.builder()
                .nickname(user.getNickname())
                .carrier(user.getCarrier())
                .membershipGradeCode(user.getMembershipGradeCode())
                .membershipGrade(user.getMembershipGradeCode())
                .membershipVerified(Boolean.TRUE.equals(user.getMembershipVerified()))
                .build();
    }

}
