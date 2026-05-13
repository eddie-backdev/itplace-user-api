package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JWTUtil jwtUtil;

    @Mock
    private CookieUtil cookieUtil;

    @Test
    void reissueConvertsRefreshTokenSignatureMismatchToInvalidTokenAndExpiresCookies() {
        AuthServiceImpl authService = new AuthServiceImpl(
                redisTemplate,
                passwordEncoder,
                userRepository,
                jwtUtil,
                cookieUtil
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setCookies(new Cookie(JWTConstants.CATEGORY_REFRESH, "stale-refresh-token"));
        when(jwtUtil.isExpired("stale-refresh-token"))
                .thenThrow(new SignatureException("JWT signature does not match"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN));

        verify(cookieUtil).expireCookie(response, JWTConstants.CATEGORY_ACCESS);
        verify(cookieUtil).expireCookie(response, JWTConstants.CATEGORY_REFRESH);
    }
}
