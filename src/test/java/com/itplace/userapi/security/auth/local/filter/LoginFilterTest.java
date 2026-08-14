package com.itplace.userapi.security.auth.local.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.exception.LoginRateLimitAuthenticationException;
import com.itplace.userapi.security.jwt.JWTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;

class LoginFilterTest {

    private AuthenticationAbuseProtectionService abuseProtectionService;
    private LoginFilter loginFilter;

    @BeforeEach
    void setUp() {
        abuseProtectionService = mock(AuthenticationAbuseProtectionService.class);
        loginFilter = new LoginFilter(
                mock(AuthenticationManager.class),
                mock(JWTUtil.class),
                mock(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                mock(CookieUtil.class),
                abuseProtectionService
        );
    }

    @Test
    void attemptAuthenticationRejectsRateLimitedLoginBeforePasswordAuthentication() {
        MockHttpServletRequest request = loginRequest();
        when(abuseProtectionService.isLoginAllowed("hong@example.com", "127.0.0.1")).thenReturn(false);

        assertThatThrownBy(() -> loginFilter.attemptAuthentication(request, new MockHttpServletResponse()))
                .isInstanceOf(LoginRateLimitAuthenticationException.class);
    }

    @Test
    void unsuccessfulAuthenticationReturnsTooManyRequestsForRateLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginFilter.unsuccessfulAuthentication(
                loginRequest(),
                response,
                new LoginRateLimitAuthenticationException(SecurityCode.AUTHENTICATION_RATE_LIMITED.getMessage())
        );

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains(SecurityCode.AUTHENTICATION_RATE_LIMITED.getCode());
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json");
        request.setContent("""
                {"email":"hong@example.com","password":"password"}
                """.getBytes());
        return request;
    }
}
