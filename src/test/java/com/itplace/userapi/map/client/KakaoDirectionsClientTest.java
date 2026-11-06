package com.itplace.userapi.map.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoDirectionsClient.DirectionsProviderException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KakaoDirectionsClientTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429})
    void providerHttpErrorsAreNotConvertedToNoRoute(int status) throws Exception {
        HttpServer server = server(status, "{}");
        server.start();
        try {
            KakaoDirectionsClient client = client(server, "test-key", Duration.ofMillis(500));
            StoreCode expected = status == 401 || status == 403 || status == 429
                    ? StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE
                    : StoreCode.DIRECTIONS_PROVIDER_ERROR;

            assertThatThrownBy(() -> client.walk(37.5, 127.0, 37.51, 127.01))
                    .isInstanceOf(DirectionsProviderException.class)
                    .extracting(error -> ((DirectionsProviderException) error).getCode())
                    .isEqualTo(expected);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutIsMappedToGatewayTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            KakaoDirectionsClient client = client(server, "test-key", Duration.ofMillis(100));
            assertThatThrownBy(() -> client.walk(37.5, 127.0, 37.51, 127.01))
                    .isInstanceOf(DirectionsProviderException.class)
                    .extracting(error -> ((DirectionsProviderException) error).getCode())
                    .isEqualTo(StoreCode.DIRECTIONS_PROVIDER_TIMEOUT);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void carUsesStandardNonAffiliateEndpointAndLatLngQuery() throws Exception {
        var pathAndQuery = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            pathAndQuery.append(exchange.getRequestURI().toString());
            byte[] body = "{\"routes\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            KakaoDirectionsClient client = client(server, "test-key", Duration.ofMillis(500));
            client.car(37.5, 127.0, 37.51, 127.01);
            assertThat(pathAndQuery).contains("/v1/directions", "origin", "destination", "37.5", "127.0");
            assertThat(pathAndQuery).doesNotContain("/affiliate/");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingKeyReturnsUnavailableWithoutMakingRequest() throws Exception {
        HttpServer server = server(200, "{}");
        server.start();
        try {
            KakaoDirectionsClient client = client(server, "", Duration.ofMillis(500));
            assertThatThrownBy(() -> client.transit(37.5, 127.0, 37.51, 127.01))
                    .isInstanceOf(DirectionsProviderException.class)
                    .extracting(error -> ((DirectionsProviderException) error).getCode())
                    .isEqualTo(StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE);
        } finally {
            server.stop(0);
        }
    }

    private KakaoDirectionsClient client(HttpServer server, String key, Duration readTimeout) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new KakaoDirectionsClient(RestClient.builder(), baseUrl, baseUrl, key,
                Duration.ofMillis(200), readTimeout);
    }

    private HttpServer server(int status, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
