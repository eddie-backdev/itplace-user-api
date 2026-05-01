package com.itplace.userapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CookieUtilTest {

    @Mock
    private JWTUtil jwtUtil;

    @Test
    void localCookieOmitsDomainAndUsesConfiguredSecurityAttributes() {
        CookieUtil cookieUtil = new CookieUtil(jwtUtil);
        ReflectionTestUtils.setField(cookieUtil, "cookieDomain", "");
        ReflectionTestUtils.setField(cookieUtil, "cookieSecure", false);
        ReflectionTestUtils.setField(cookieUtil, "cookieSameSite", "Lax");
        when(jwtUtil.getAccessTokenValidityInMS()).thenReturn(1_800_000L);

        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieUtil.setAccessTokenCookie(response, "access-token");

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains(JWTConstants.CATEGORY_ACCESS + "=access-token");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).doesNotContain("Domain=");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void productionCookieIncludesDomainSecureSameSiteAndExpirationUsesSameTuple() {
        CookieUtil cookieUtil = new CookieUtil(jwtUtil);
        ReflectionTestUtils.setField(cookieUtil, "cookieDomain", "itplace.click");
        ReflectionTestUtils.setField(cookieUtil, "cookieSecure", true);
        ReflectionTestUtils.setField(cookieUtil, "cookieSameSite", "None");

        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieUtil.expireCookie(response, JWTConstants.CATEGORY_REFRESH);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains(JWTConstants.CATEGORY_REFRESH + "=");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("Domain=itplace.click");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=None");
        assertThat(setCookie).contains("Max-Age=0");
    }
}
