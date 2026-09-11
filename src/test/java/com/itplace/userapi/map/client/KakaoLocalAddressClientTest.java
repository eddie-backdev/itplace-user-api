package com.itplace.userapi.map.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class KakaoLocalAddressClientTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowAddressServerFallsBackWithinConfiguredReadTimeout(boolean stallsAfterHeaders) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/v2/local/geo/coord2address.json", exchange -> {
            if (stallsAfterHeaders) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 128);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new KakaoLocalAddressClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    Duration.ofMillis(200), Duration.ofMillis(200));
            long start = System.nanoTime();
            assertThat(client.findRegionAddress(37.5, 127)).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
