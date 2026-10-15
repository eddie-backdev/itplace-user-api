package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.SecurityCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityConfigApiAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void protectedApiAuthenticationFailureReturnsJson401() throws Exception {
        var entryPoint = SecurityConfig.apiAuthenticationEntryPoint(objectMapper);
        var request = new MockHttpServletRequest("GET", "/api/v1/benefit-usages");
        request.setServletPath("/api/v1/benefit-usages");
        var response = new MockHttpServletResponse();

        entryPoint.commence(request, response, null);

        assertThat(SecurityConfig.apiAuthenticationRequestMatcher().matches(request)).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(objectMapper.readTree(response.getContentAsString()).path("code").asText())
                .isEqualTo(SecurityCode.UNAUTHORIZED_ACCESS.getCode());
    }

    @Test
    void oauthBrowserRoutesDoNotUseTheApiJsonEntryPoint() {
        var matcher = SecurityConfig.apiAuthenticationRequestMatcher();
        var authorizationRequest = new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
        authorizationRequest.setServletPath("/oauth2/authorization/kakao");
        var callbackRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        callbackRequest.setServletPath("/login/oauth2/code/kakao");

        assertThat(matcher.matches(authorizationRequest)).isFalse();
        assertThat(matcher.matches(callbackRequest)).isFalse();
    }
}
