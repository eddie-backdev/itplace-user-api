package com.itplace.userapi.security.verification.recaptcha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.itplace.userapi.security.verification.recaptcha.dto.request.RecaptchaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class RecaptchaServiceTest {

    private static final String URL = "https://captcha.invalid/siteverify";

    @Test
    void nullEmptyAndBlankTokensAreRejectedWithoutCallingProvider() {
        ExchangeFunction exchange = mock(ExchangeFunction.class);
        RecaptchaService service = service(exchange);

        assertThat(service.verifyRecaptcha(request(null))).isFalse();
        assertThat(service.verifyRecaptcha(request(""))).isFalse();
        assertThat(service.verifyRecaptcha(request(" \t "))).isFalse();

        verify(exchange, never()).exchange(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsProviderSuccessFlagAndFailsClosedForEmptyOrMalformedBodies() {
        assertThat(verifyWithBody("{\"success\":true}")).isTrue();
        assertThat(verifyWithBody("{\"success\":false}")).isFalse();
        assertThat(verifyWithBody(null)).isFalse();
        assertThat(verifyWithBody("not-json")).isFalse();
    }

    @Test
    void providerTransportFailureIsNotAcceptedAsVerified() {
        ExchangeFunction exchange = request -> Mono.error(new IllegalStateException("connection failed"));

        assertThat(service(exchange).verifyRecaptcha(request("captcha-token"))).isFalse();
    }

    private boolean verifyWithBody(String body) {
        ExchangeFunction exchange = request -> {
            ClientResponse.Builder response = ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            return Mono.just(body == null ? response.build() : response.body(body).build());
        };
        return service(exchange).verifyRecaptcha(request("captcha-token"));
    }

    private RecaptchaService service(ExchangeFunction exchange) {
        RecaptchaService service = new RecaptchaService(WebClient.builder().exchangeFunction(exchange).build());
        ReflectionTestUtils.setField(service, "recaptchaUrl", URL);
        ReflectionTestUtils.setField(service, "recaptchaSecret", "test-secret");
        return service;
    }

    private RecaptchaRequest request(String token) {
        RecaptchaRequest request = new RecaptchaRequest();
        request.setRecaptchaToken(token);
        return request;
    }
}
