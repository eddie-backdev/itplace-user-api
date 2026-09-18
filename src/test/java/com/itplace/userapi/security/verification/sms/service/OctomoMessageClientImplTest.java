package com.itplace.userapi.security.verification.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class OctomoMessageClientImplTest {
    private HttpServer server;
    private OctomoMessageClientImpl client;
    private String responseBody = "{\"exists\":false}";
    private int responseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/public/message/exists", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new OctomoMessageClientImpl(RestClient.builder(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void explicitNotReceivedResponseRemainsPending() {
        assertThat(client.exists("01012345678", "AB7K9Q")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"verified\":true}", "{\"data\":{\"exists\":true}}", "{\"exists\":\"true\"}"})
    void supportedReceivedResponsesSucceed(String body) {
        responseBody = body;
        assertThat(client.exists("01012345678", "AB7K9Q")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "{\"message\":\"upstream error\"}", "{\"verified\":null}"})
    void invalidProviderResponsesAreUnavailableInsteadOfPending(String body) {
        responseBody = body;
        assertUnavailable();
    }

    @Test
    void providerHttpFailureIsUnavailableInsteadOfPending() {
        responseStatus = 502;
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> client.exists("01012345678", "AB7K9Q"))
                .isInstanceOfSatisfying(SmsVerificationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(SecurityCode.SMS_PROVIDER_UNAVAILABLE));
    }
}
