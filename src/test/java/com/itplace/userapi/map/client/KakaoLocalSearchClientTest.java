package com.itplace.userapi.map.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KakaoLocalSearchClientTest {

    @Test
    void usesOfficialAddressAndKeywordEndpointsAndOnlyAddsCenterToKeywordSearch() throws Exception {
        var received = new java.util.ArrayList<String>();
        var auth = new java.util.ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().toString());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"documents\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            KakaoLocalSearchClient client = client(server, "test-key", Duration.ofMillis(500));
            client.address("서울역");
            client.places("서울역", 37.5, 127.0);

            assertThat(received.get(0)).contains("/v2/local/search/address.json", "query=");
            assertThat(received.get(0)).doesNotContain("&x=", "&y=");
            assertThat(received.get(1)).contains("/v2/local/search/keyword.json", "x=127", "y=37.5", "sort=distance");
            assertThat(received.get(1)).doesNotContain("radius=");
            assertThat(auth).containsOnly("KakaoAK test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderFailuresAndTimeoutsWithoutLeakingThemAsEmptyResults() throws Exception {
        HttpServer denied = server(403, "{}");
        denied.start();
        try {
            assertThatThrownBy(() -> client(denied, "test-key", Duration.ofMillis(500)).address("서울역"))
                    .isInstanceOf(LocalSearchException.class)
                    .extracting(error -> ((LocalSearchException) error).getCode())
                    .isEqualTo(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
        } finally {
            denied.stop(0);
        }

        HttpServer delayed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        delayed.setExecutor(executor);
        delayed.createContext("/", exchange -> {
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        delayed.start();
        try {
            assertThatThrownBy(() -> client(delayed, "test-key", Duration.ofMillis(100)).places("서울역", null, null))
                    .isInstanceOf(LocalSearchException.class)
                    .extracting(error -> ((LocalSearchException) error).getCode())
                    .isEqualTo(StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT);
        } finally {
            release.countDown();
            delayed.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void missingKeyDoesNotMakeProviderRequest() throws Exception {
        HttpServer server = server(200, "{\"documents\":[]}");
        server.start();
        try {
            assertThatThrownBy(() -> client(server, "", Duration.ofMillis(500)).address("서울역"))
                    .isInstanceOf(LocalSearchException.class)
                    .extracting(error -> ((LocalSearchException) error).getCode())
                    .isEqualTo(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
        } finally {
            server.stop(0);
        }
    }

    private KakaoLocalSearchClient client(HttpServer server, String key, Duration timeout) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new KakaoLocalSearchClient(RestClient.builder(), baseUrl, key,
                Duration.ofMillis(200), timeout);
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
