package com.itplace.userapi.ai.rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.itplace.userapi.map.service.StoreSearchServiceImpl;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ElasticsearchConfigTest {
    @Test
    void mapSearchUsesItsDeadlineWithoutChangingSharedClientTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        var release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().endsWith("_msearch")) {
                    release.await(5, TimeUnit.SECONDS);
                } else {
                    // 공유 client는 지도용 200ms보다 긴 응답도 기존 socket 제한으로 받는다.
                    release.await(1500, TimeUnit.MILLISECONDS);
                    exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var config = new ElasticsearchConfig();
            ReflectionTestUtils.setField(config, "host", "127.0.0.1");
            ReflectionTestUtils.setField(config, "port", server.getAddress().getPort());
            ReflectionTestUtils.setField(config, "scheme", "http");
            ReflectionTestUtils.setField(config, "connectTimeoutMs", 2000);
            ReflectionTestUtils.setField(config, "socketTimeoutMs", 5000);
            try (var transport = config.elasticsearchTransport()) {
                ElasticsearchClient shared = config.elasticsearchClient(transport);
                var originalOptions = shared._transportOptions();
                var service = new StoreSearchServiceImpl(shared, 2000, 200);
                ElasticsearchClient mapClient = (ElasticsearchClient) ReflectionTestUtils.getField(service, "esClient");
                var mapRequest = ((RestClientOptions) mapClient._transportOptions()).restClientRequestOptions().getRequestConfig();
                assertThat(mapClient._transport()).isSameAs(shared._transport());
                assertThat(mapRequest.getSocketTimeout()).isEqualTo(200);
                assertThat(mapRequest.getConnectTimeout()).isEqualTo(2000);
                assertThat(mapRequest.getConnectionRequestTimeout()).isEqualTo(2000);
                long start = System.nanoTime();
                assertThatThrownBy(() -> service.searchByKeyword("카페", null))
                        .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(shared._transportOptions()).isSameAs(originalOptions);
                assertThat(shared.ping().value()).isTrue();
            }
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void appliesConfiguredSocketTimeoutToActualClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var config = new ElasticsearchConfig();
            ReflectionTestUtils.setField(config, "host", "127.0.0.1");
            ReflectionTestUtils.setField(config, "port", server.getAddress().getPort());
            ReflectionTestUtils.setField(config, "scheme", "http");
            ReflectionTestUtils.setField(config, "connectTimeoutMs", 200);
            ReflectionTestUtils.setField(config, "socketTimeoutMs", 200);
            try (var transport = config.elasticsearchTransport()) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> config.elasticsearchClient(transport).info()).isInstanceOf(IOException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            }
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
