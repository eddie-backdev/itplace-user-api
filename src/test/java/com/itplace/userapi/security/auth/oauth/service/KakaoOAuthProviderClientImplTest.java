package com.itplace.userapi.security.auth.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoUserProfile;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KakaoOAuthProviderClientImplTest {

    @Test
    void exchangesAuthorizationCodeAndParsesVerifiedProfileWithoutRealNetwork() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                assertThat(request.method().name()).isEqualTo("POST");
                assertThat(request.url().toString()).isEqualTo("https://provider.invalid/token");
                return response("{\"access_token\":\"provider-access\"}");
            }
            assertThat(request.method().name()).isEqualTo("GET");
            assertThat(request.url().toString()).isEqualTo("https://provider.invalid/me");
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-access");
            return response("""
                    {"id":12345,"kakao_account":{"email":"person@example.com","is_email_valid":true,
                    "is_email_verified":true,"profile":{"nickname":"Person"}}}
                    """);
        };

        KakaoUserProfile profile = client(exchange).fetchUser("authorization-code", "custom://callback");

        assertThat(profile.providerId()).isEqualTo("12345");
        assertThat(profile.email()).isEqualTo("person@example.com");
        assertThat(profile.nickname()).isEqualTo("Person");
        assertThat(profile.emailValid()).isTrue();
        assertThat(profile.emailVerified()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsMissingTokenPayloadWithoutRequestingProfile() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return response("{}");
        };

        assertThatThrownBy(() -> client(exchange).fetchUser("code", null))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsProfileWithoutProviderId() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> calls.incrementAndGet() == 1
                ? response("{\"access_token\":\"token\"}")
                : response("{\"kakao_account\":{\"email\":\"person@example.com\"}}");

        assertThatThrownBy(() -> client(exchange).fetchUser("code", null))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsNonnumericProviderId() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> calls.incrementAndGet() == 1
                ? response("{\"access_token\":\"token\"}")
                : response("{\"id\":\"provider-user\"}");

        assertThatThrownBy(() -> client(exchange).fetchUser("code", null))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void mapsProviderNetworkFailureToInvalidCredentials() {
        ExchangeFunction exchange = request -> Mono.error(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> client(exchange).fetchUser("code", null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsMalformedProviderPayload() {
        ExchangeFunction exchange = request -> response("not-json");

        assertThatThrownBy(() -> client(exchange).fetchUser("code", null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private KakaoOAuthProviderClientImpl client(ExchangeFunction exchange) {
        KakaoOAuthProviderClientImpl client = new KakaoOAuthProviderClientImpl(
                WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(client, "clientId", "test-client");
        ReflectionTestUtils.setField(client, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "defaultRedirectUri", "itplace://default/callback");
        ReflectionTestUtils.setField(client, "tokenUri", "https://provider.invalid/token");
        ReflectionTestUtils.setField(client, "userInfoUri", "https://provider.invalid/me");
        return client;
    }

    private Mono<ClientResponse> response(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
