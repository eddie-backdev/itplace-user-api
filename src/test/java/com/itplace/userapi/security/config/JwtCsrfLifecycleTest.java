package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.security.jwt.JWTFilter;
import com.itplace.userapi.security.jwt.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.SessionManagementFilter;

class JwtCsrfLifecycleTest {

    private static final String SECRET = "test-secret-012345678901234567890123456789";
    private static final String ACCESS_TOKEN = new JWTUtil(SECRET, 60, 120)
            .createJwt(7L, "ROLE_USER", "access");
    private static final String CSRF_TOKEN = "stable-csrf-token";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedGetAndRepeatedPatchesKeepCsrfCookie() throws Exception {
        MockHttpServletResponse getResponse = perform("GET", "/api/v1/users", CSRF_TOKEN, CSRF_TOKEN);
        assertThat(getResponse.getStatus()).isEqualTo(200);
        assertNoCsrfCookieChange(getResponse);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse patchResponse = perform("PATCH", "/api/v1/users", CSRF_TOKEN, CSRF_TOKEN);
            assertThat(patchResponse.getStatus()).isEqualTo(200);
            assertNoCsrfCookieChange(patchResponse);
        }
    }

    @Test
    void missingOrMismatchedCsrfTokenStillFails() throws Exception {
        assertThat(perform("PATCH", "/api/v1/users", null, null).getStatus()).isEqualTo(403);
        assertThat(perform("PATCH", "/api/v1/users", CSRF_TOKEN, "wrong-token").getStatus()).isEqualTo(403);
    }

    @Test
    void aDifferentAuthenticationStillRotatesCsrfCookie() throws Exception {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        SessionAuthenticationStrategy strategy = SecurityConfig.jwtAwareCsrfAuthenticationStrategy(repository,
                requestHandler);
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login", CSRF_TOKEN, CSRF_TOKEN);
        request.setAttribute(JWTFilter.AUTHENTICATION_ATTRIBUTE,
                new UsernamePasswordAuthenticationToken("jwt-user", null, java.util.List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        strategy.onAuthentication(new UsernamePasswordAuthenticationToken("new-user", null, java.util.List.of()),
                request, response);

        assertThat(csrfCookie(response)).isNotNull();
        assertThat(csrfCookie(response).getMaxAge()).isZero();
    }

    private MockHttpServletResponse perform(String method, String path, String cookieToken, String headerToken)
            throws Exception {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        CsrfFilter csrfFilter = new CsrfFilter(repository);
        csrfFilter.setRequestHandler(requestHandler);
        SessionManagementFilter sessionManagementFilter = new SessionManagementFilter(
                new NullSecurityContextRepository(),
                SecurityConfig.jwtAwareCsrfAuthenticationStrategy(repository, requestHandler));
        JWTFilter jwtFilter = new JWTFilter(new JWTUtil(SECRET, 60, 120));
        MockHttpServletRequest request = request(method, path, cookieToken, headerToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain endpoint = (servletRequest, servletResponse) -> { };
        FilterChain afterSessionManagement = (servletRequest, servletResponse) ->
                sessionManagementFilter.doFilter(servletRequest, servletResponse, endpoint);
        FilterChain afterJwt = (servletRequest, servletResponse) ->
                jwtFilter.doFilter(servletRequest, servletResponse, afterSessionManagement);
        csrfFilter.doFilter(request, response, afterJwt);
        return response;
    }

    private MockHttpServletRequest request(String method, String path, String cookieToken, String headerToken) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        Cookie accessCookie = new Cookie("access", ACCESS_TOKEN);
        if (cookieToken == null) {
            request.setCookies(accessCookie);
        } else {
            request.setCookies(accessCookie, new Cookie("XSRF-TOKEN", cookieToken));
        }
        if (headerToken != null) {
            request.addHeader("X-XSRF-TOKEN", headerToken);
        }
        return request;
    }

    private void assertNoCsrfCookieChange(MockHttpServletResponse response) {
        assertThat(csrfCookie(response)).isNull();
    }

    private Cookie csrfCookie(MockHttpServletResponse response) {
        for (Cookie cookie : response.getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }
}
