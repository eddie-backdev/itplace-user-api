package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

class SecurityConfigAuthorizationRulesTest {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Test
    void unknownApiPathIsNotPublic() {
        assertThat(isPublicEndpoint("/api/v1/security/probe")).isFalse();
        assertThat(isPublicReadEndpoint("/api/v1/security/probe")).isFalse();
        assertThat(isPublicPostEndpoint("/api/v1/security/probe")).isFalse();
    }

    @Test
    void sensitiveUserAndFavoriteEndpointsRequireAuthentication() {
        assertThat(isPublicReadEndpoint("/api/v1/users")).isFalse();
        assertThat(isPublicPostEndpoint("/api/v1/favorites")).isFalse();
        assertThat(isPublicReadEndpoint("/api/v1/favorites/search")).isFalse();

        assertThat(isAuthenticatedEndpoint("/api/v1/users")).isTrue();
        assertThat(isAuthenticatedEndpoint("/api/v1/users/changePassword")).isTrue();
        assertThat(isAuthenticatedEndpoint("/api/v1/favorites")).isTrue();
        assertThat(isAuthenticatedEndpoint("/api/v1/favorites/search")).isTrue();
    }

    @Test
    void passwordRecoveryRoutesStayPublicBeforeUsersWildcard() {
        assertThat(isPublicPostEndpoint("/api/v1/users/findPassword")).isTrue();
        assertThat(isPublicPostEndpoint("/api/v1/users/findPassword/confirm")).isTrue();
        assertThat(isPublicPostEndpoint("/api/v1/users/resetPassword")).isTrue();
    }

    @Test
    void intendedPublicRoutesStayPublic() {
        assertThat(isPublicEndpoint("/api-docs")).isTrue();
        assertThat(isPublicEndpoint("/oauth2/authorization/kakao")).isTrue();
        assertThat(isPublicEndpoint("/login/oauth2/code/kakao")).isTrue();
        assertThat(isPublicReadEndpoint("/api/v1/auth/csrf")).isTrue();
        assertThat(isPublicReadEndpoint("/api/v1/benefits/1")).isTrue();
        assertThat(isPublicReadEndpoint("/api/v1/maps/nearby")).isTrue();
        assertThat(isPublicPostEndpoint("/api/v1/auth/signUp")).isTrue();
        assertThat(isPublicPostEndpoint("/api/v1/auth/reissue")).isTrue();
        assertThat(isPublicPostEndpoint("/api/v1/internal/benefits/import")).isTrue();
    }

    private boolean isPublicEndpoint(String path) {
        return matchesAny(SecurityConfig.publicEndpoints(), path);
    }

    private boolean isPublicReadEndpoint(String path) {
        return matchesAny(SecurityConfig.publicReadEndpoints(), path);
    }

    private boolean isPublicPostEndpoint(String path) {
        return matchesAny(SecurityConfig.publicPostEndpoints(), path);
    }

    private boolean isAuthenticatedEndpoint(String path) {
        return matchesAny(SecurityConfig.authenticatedEndpoints(), path);
    }

    private boolean matchesAny(String[] patterns, String path) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
