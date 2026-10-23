package com.itplace.userapi.security.jwt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

class JWTFilterTest {
    private static final String SECRET = "test-secret-012345678901234567890123456789";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenIsParsedOnceAndDownstreamExceptionIsNotRetried() throws Exception {
        JWTUtil util = spy(new JWTUtil(SECRET, 60, 120));
        var request = request(util.createJwt(7L, "ROLE_USER", "access"));
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new JwtException("downstream")).when(chain).doFilter(request, response);
        assertThatThrownBy(() -> new JWTFilter(util).doFilter(request, response, chain)).hasMessage("downstream");
        verify(util).getClaims(request.getCookies()[0].getValue());
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void savedJwtContextIsRestoredForAsyncRedispatch() throws Exception {
        JWTUtil util = new JWTUtil(SECRET, 60, 120);
        RequestAttributeSecurityContextRepository repository = new RequestAttributeSecurityContextRepository();
        var request = request(util.createJwt(7L, "ROLE_USER", "access"));
        new JWTFilter(util, repository).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        SecurityContextHolder.clearContext();

        request.setDispatcherType(DispatcherType.ASYNC);
        var restored = repository.loadDeferredContext(request).get();
        assertThat(restored.getAuthentication()).isNotNull();
        assertThat(restored.getAuthentication().getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "malformed", "missing_id", "wrong_role", "refresh"})
    void invalidTokensCannotAuthenticate(String scenario) throws Exception {
        JWTUtil util = new JWTUtil(SECRET, scenario.equals("expired") ? -1 : 60, 120);
        String token = scenario.equals("malformed") ? "invalid" : util.createJwt(
                scenario.equals("missing_id") ? null : 7L,
                scenario.equals("wrong_role") ? "UNKNOWN" : "ROLE_USER",
                scenario.equals("refresh") ? "refresh" : "access");
        var request = request(token);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        RequestAttributeSecurityContextRepository repository = new RequestAttributeSecurityContextRepository();
        new JWTFilter(util, repository).doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
        if (scenario.equals("refresh")) {
            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(chain);
        } else {
            verify(chain).doFilter(request, response);
        }
    }

    private MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/api/v1/benefits");
        request.setCookies(new Cookie("access", token));
        return request;
    }
}
