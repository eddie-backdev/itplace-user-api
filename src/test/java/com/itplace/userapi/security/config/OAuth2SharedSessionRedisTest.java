package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OAuth2SharedSessionRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4").withExposedPorts(6379);

    @Test
    void authorizationStartedOnAIsValidatedAndConsumedOnB() throws Exception {
        try (var a = instance(); var b = instance()) {
            var repository = new HttpSessionOAuth2AuthorizationRequestRepository();
            var authorization = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri("https://provider.example/authorize").clientId("client")
                    .redirectUri("https://api.example/login/oauth2/code/kakao").state("expected-state").build();
            var start = new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
            var response = new MockHttpServletResponse();
            filter(a).doFilter(start, response, (request, reply) -> repository.saveAuthorizationRequest(
                    authorization, (HttpServletRequest) request, (HttpServletResponse) reply));
            Cookie cookie = response.getCookie("ITPLACE_OAUTH_SESSION");
            assertThat(cookie).isNotNull();
            assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Lax");

            filter(b).doFilter(callback(cookie, "wrong-state"), new MockHttpServletResponse(), (request, reply) ->
                    assertThat(repository.removeAuthorizationRequest((HttpServletRequest) request,
                            (HttpServletResponse) reply)).isNull());
            filter(b).doFilter(callback(cookie, "expected-state"), new MockHttpServletResponse(), (request, reply) -> {
                var restored = repository.removeAuthorizationRequest((HttpServletRequest) request, (HttpServletResponse) reply);
                assertThat(restored).isNotNull();
                assertThat(restored.getState()).isEqualTo("expected-state");
                assertThat(((HttpServletRequest) request).getSession(false).getMaxInactiveInterval()).isEqualTo(600);
            });
            filter(a).doFilter(callback(cookie, "expected-state"), new MockHttpServletResponse(), (request, reply) ->
                    assertThat(repository.removeAuthorizationRequest((HttpServletRequest) request,
                            (HttpServletResponse) reply)).isNull());

            var publicResponse = new MockHttpServletResponse();
            filter(a).doFilter(new MockHttpServletRequest("GET", "/api/v1/benefits"), publicResponse, (request, reply) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isNull());
            assertThat(publicResponse.getHeader("Set-Cookie")).isNull();
        }
    }

    private MockHttpServletRequest callback(Cookie cookie, String state) {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        request.setCookies(cookie);
        request.setParameter("state", state);
        request.setParameter("code", "test-code");
        return request;
    }

    private AnnotationConfigWebApplicationContext instance() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RedisConfig.class, OAuth2SessionConfig.class);
        context.refresh();
        return context;
    }

    private SessionRepositoryFilter<?> filter(AnnotationConfigWebApplicationContext context) {
        return context.getBean(SessionRepositoryFilter.class);
    }

    @Configuration
    static class RedisConfig {
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }
    }
}
