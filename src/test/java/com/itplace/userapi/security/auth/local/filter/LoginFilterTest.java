package com.itplace.userapi.security.auth.local.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.exception.LoginRateLimitAuthenticationException;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;

class LoginFilterTest {

    private AuthenticationAbuseProtectionService abuseProtectionService;
    private AuthenticationManager authenticationManager;
    private JWTUtil jwtUtil;
    private StringRedisTemplate redisTemplate;
    private CookieUtil cookieUtil;
    private LoginFilter loginFilter;

    @BeforeEach
    void setUp() {
        abuseProtectionService = mock(AuthenticationAbuseProtectionService.class);
        authenticationManager = mock(AuthenticationManager.class);
        jwtUtil = mock(JWTUtil.class);
        redisTemplate = mock(StringRedisTemplate.class);
        cookieUtil = mock(CookieUtil.class);
        loginFilter = new LoginFilter(
                authenticationManager,
                jwtUtil,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                cookieUtil,
                abuseProtectionService
        );
        loginFilter.setFilterProcessesUrl("/api/v1/auth/login");
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

    @Test
    void filterChainReturnsBadRequestForEmptyOrMalformedJsonWithoutAuthenticationOrLockout() throws Exception {
        for (byte[] body : List.of(new byte[0], "{".getBytes())) {
            MockHttpServletRequest request = loginRequest(body);
            MockHttpServletResponse response = new MockHttpServletResponse();

            loginFilter.doFilter(request, response, (req, res) -> { });

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains(SecurityCode.INVALID_INPUT_VALUE.getCode());
        }

        verify(authenticationManager, never()).authenticate(org.mockito.ArgumentMatchers.any());
        verify(abuseProtectionService, never()).isLoginAllowed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(abuseProtectionService, never()).recordLoginFailure(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void filterChainRecordsWrongCredentialsAsUnauthorized() throws Exception {
        when(abuseProtectionService.isLoginAllowed("hong@example.com", "127.0.0.1")).thenReturn(true);
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any(Authentication.class)))
                .thenThrow(new BadCredentialsException("bad credentials"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginFilter.doFilter(loginRequest(), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(SecurityCode.LOGIN_FAIL.getCode());
        verify(abuseProtectionService).recordLoginFailure("hong@example.com");
        verify(cookieUtil, never()).setTokensToCookie(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void filterChainCompletesSuccessfulLogin() throws Exception {
        when(abuseProtectionService.isLoginAllowed("hong@example.com", "127.0.0.1")).thenReturn(true);
        User user = User.builder().id(7L).email("hong@example.com").nickname("Hong")
                .role(Role.USER).build();
        CustomUserDetails details = new CustomUserDetails(user, "hash");
        Authentication result = new UsernamePasswordAuthenticationToken(details, "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any(Authentication.class))).thenReturn(result);
        when(jwtUtil.createJwt(7L, "ROLE_USER", "access")).thenReturn("access-token");
        when(jwtUtil.createJwt(7L, "ROLE_USER", "refresh")).thenReturn("refresh-token");
        when(jwtUtil.getRefreshTokenValidityInMS()).thenReturn(60_000L);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginFilter.doFilter(loginRequest(), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(SecurityCode.LOGIN_SUCCESS.getCode());
        verify(cookieUtil).setTokensToCookie(response, "access-token", "refresh-token");
        verify(valueOperations).set("RT:7", "refresh-token", 60_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        verify(abuseProtectionService).clearLoginFailures("hong@example.com");
    }

    private MockHttpServletRequest loginRequest() {
        return loginRequest("""
                {"email":"hong@example.com","password":"password"}
                """.getBytes());
    }

    private MockHttpServletRequest loginRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
