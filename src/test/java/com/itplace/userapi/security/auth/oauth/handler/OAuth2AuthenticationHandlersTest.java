package com.itplace.userapi.security.auth.oauth.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.auth.local.filter.LoginFilter;
import com.itplace.userapi.security.auth.oauth.dto.CustomOAuth2User;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

class OAuth2AuthenticationHandlersTest {

    @Test
    void newUserRedirectContainsProfileOnlyAndSetsShortLivedTempCookie() throws IOException, ServletException {
        JWTUtil jwtUtil = mock(JWTUtil.class);
        CookieUtil cookieUtil = mock(CookieUtil.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(jwtUtil, cookieUtil, redis);
        ReflectionTestUtils.setField(handler, "newUserRedirectUri", "https://app.invalid/signup");
        CustomOAuth2User principal = new CustomOAuth2User(
                mock(org.springframework.security.oauth2.core.user.OAuth2User.class),
                "kakao", "provider-1", null, "person@example.com", "A User");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtUtil.createTempJwt("kakao", "provider-1", "person@example.com", "A User"))
                .thenReturn("one-time-temp-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://app.invalid/signup?verifiedType=oauth&email=person%40example.com&nickname=A+User");
        verify(cookieUtil).setTempTokenCookie(response, "one-time-temp-token", 600L);
        verify(redis, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getRedirectedUrl()).doesNotContain("one-time-temp-token", "provider-1");
    }

    @Test
    void existingUserStoresRefreshSessionAndRedirectsWithoutTokenQueryParameters() throws IOException, ServletException {
        JWTUtil jwtUtil = mock(JWTUtil.class);
        CookieUtil cookieUtil = mock(CookieUtil.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(jwtUtil, cookieUtil, redis);
        ReflectionTestUtils.setField(handler, "existUserRedirectUri", "https://app.invalid/home");
        User user = User.builder().id(7L).role(Role.USER).build();
        CustomOAuth2User principal = new CustomOAuth2User(
                mock(org.springframework.security.oauth2.core.user.OAuth2User.class),
                "kakao", "provider-1", user, "person@example.com", "Person");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS)).thenReturn("access-secret");
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_REFRESH)).thenReturn("refresh-secret");
        when(jwtUtil.getRefreshTokenValidityInMS()).thenReturn(86_400_000L);
        when(redis.opsForValue()).thenReturn(valueOperations);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://app.invalid/home");
        verify(valueOperations).set(LoginFilter.REFRESH_TOKEN_PREFIX + 7L, "refresh-secret", 86_400_000L,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        verify(cookieUtil).setTokensToCookie(response, "access-secret", "refresh-secret");
        assertThat(response.getRedirectedUrl()).doesNotContain("access-secret", "refresh-secret");
    }

    @Test
    void failureHandlerRedirectDoesNotExposeProviderExceptionDetails() throws IOException, ServletException {
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(), response, new OAuth2AuthenticationException("sensitive-provider-error"));

        assertThat(response.getRedirectedUrl()).contains("error=true");
        assertThat(response.getRedirectedUrl()).contains("OAuth%202.0%20%EC%9D%B8%EC%A6%9D%EC%97%90%20%EC%8B%A4%ED%8C%A8%ED%96%88%EC%8A%B5%EB%8B%88%EB%8B%A4.");
        assertThat(response.getRedirectedUrl()).doesNotContain("sensitive-provider-error");
    }
}
