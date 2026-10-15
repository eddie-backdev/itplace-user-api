package com.itplace.userapi.map.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

class KakaoLocalAddressClientTest {
    @Test
    void addressLookupFailureDoesNotLogPreciseLocationOrRequestDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/geo/coord2address.json", exchange -> {
            byte[] body = "lookup failed near 37.5665,127.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Logger logger = (Logger) LoggerFactory.getLogger(KakaoLocalAddressClient.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var client = new KakaoLocalAddressClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    Duration.ofMillis(200), Duration.ofMillis(200));

            assertThat(client.findRegionAddress(37.5665, 127.0)).isEmpty();

            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("errorType=");
            assertThat(logs).doesNotContain("37.5665", "127.0", "lookup failed", "test-key");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            server.stop(0);
        }
    }

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
