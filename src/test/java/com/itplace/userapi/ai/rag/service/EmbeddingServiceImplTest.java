package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.recommend.config.OpenAiWebClientConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class EmbeddingServiceImplTest {

    @Test
    void localProviderUsesPinnedKureIdentityLoopbackEndpointAndNoAuthorization() {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestUrl = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            requestUrl.set(request.url().toString());
            authorization.set(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            int call = requestCount.getAndIncrement();
            return localResponse(call == 0 ? 4 : 1);
        }).build();
        EmbeddingSettings settings = settings("local", EmbeddingSettings.KURE_MODEL, 1024, "http://127.0.0.1:18081");
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(webClient, settings);

        List<List<Float>> vectors = service.embedBatch(List.of("a", "b", "c", "d", "e"));

        assertThat(vectors).hasSize(5).allSatisfy(vector -> assertThat(vector).hasSize(1024));
        assertThat(requestCount).hasValue(2);
        assertThat(requestUrl).hasValue("http://127.0.0.1:18081/v1/embeddings");
        assertThat(authorization).hasValue(null);
        assertThat(settings.modelIdentity()).isEqualTo("local:" + EmbeddingSettings.KURE_MODEL);
    }

    @Test
    void localProviderRequiresPinnedModelDimensionsAndLoopbackHttp() {
        assertThatThrownBy(() -> settings("local", "other-model", 1024, "http://127.0.0.1:18081"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned KURE");
        assertThatThrownBy(() -> settings("local", EmbeddingSettings.KURE_MODEL, 1536, "http://127.0.0.1:18081"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1024");
        assertThatThrownBy(() -> settings("local", EmbeddingSettings.KURE_MODEL, 1024, "https://127.0.0.1:18081"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
    }

    @Test
    void localProviderAllowsOnlyExactOptedInTrustedHostsAndValidRootHttpUrls() {
        EmbeddingSettings trusted = new EmbeddingSettings("local", EmbeddingSettings.KURE_MODEL, 1024,
                "http://KURE-EMBEDDING:18081/", "kure-embedding,other-service");
        assertThat(trusted.baseUrl()).isEqualTo("http://KURE-EMBEDDING:18081/");
        assertThat(settings("local", EmbeddingSettings.KURE_MODEL, 1024, "http://localhost:18081").baseUrl())
                .isEqualTo("http://localhost:18081");

        for (String baseUrl : List.of("http://kure-embedding.evil:18081", "http://kure-embedding:18081/v1",
                "http://user@kure-embedding:18081", "http://kure-embedding:18081?x=1",
                "http://kure-embedding:18081#fragment", "http://kure-embedding:99999")) {
            assertThatThrownBy(() -> new EmbeddingSettings("local", EmbeddingSettings.KURE_MODEL, 1024,
                    baseUrl, "kure-embedding")).isInstanceOf(IllegalStateException.class);
        }
        for (String allowlist : List.of("*.internal", "kure-embedding:18081", "https://kure-embedding",
                "kure-embedding?x=1", "kure-embedding#fragment")) {
            assertThatThrownBy(() -> new EmbeddingSettings("local", EmbeddingSettings.KURE_MODEL, 1024,
                    "http://kure-embedding:18081", allowlist)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void embedBatchRestoresOrderUsingResponseIndicesAndValidatesVectors() {
        AtomicInteger requestCount = new AtomicInteger();
        EmbeddingServiceImpl service = service(request -> {
            requestCount.incrementAndGet();
            return response("text-embedding-3-small", List.of(1, 0));
        });

        List<List<Float>> vectors = service.embedBatch(List.of("first", "second"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1536).first().isEqualTo(1f);
        assertThat(vectors.get(1)).hasSize(1536).first().isEqualTo(2f);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void splitsLargeBatchesIntoAtMost32Inputs() {
        AtomicInteger requestCount = new AtomicInteger();
        EmbeddingServiceImpl service = service(request -> {
            int call = requestCount.getAndIncrement();
            return response("text-embedding-3-small", call == 0
                    ? java.util.stream.IntStream.range(0, 32).boxed().toList() : List.of(0));
        });

        List<List<Float>> vectors = service.embedBatch(java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "text " + index).toList());

        assertThat(vectors).hasSize(33);
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void rejectsBlankInputBeforeMakingARequest() {
        AtomicInteger requestCount = new AtomicInteger();
        EmbeddingServiceImpl service = service(request -> {
            requestCount.incrementAndGet();
            return response("text-embedding-3-small", List.of(0));
        });

        assertThatThrownBy(() -> service.embedBatch(List.of("valid", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void rejectsModelMismatchAndDuplicateIndices() {
        EmbeddingServiceImpl wrongModel = service(request -> response("other-model", List.of(0)));
        assertThatThrownBy(() -> wrongModel.embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");

        EmbeddingServiceImpl duplicateIndices = service(request -> response("text-embedding-3-small", List.of(0, 0)));
        assertThatThrownBy(() -> duplicateIndices.embedBatch(List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index");
    }

    @Test
    void rejectsWrongSizedAndZeroVectors() {
        EmbeddingServiceImpl shortVector = service(request -> jsonResponse("""
                {"model":"text-embedding-3-small","data":[{"index":0,"embedding":[1.0]}]}
                """));
        assertThatThrownBy(() -> shortVector.embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1536");

        String zeros = "0.0,".repeat(1535) + "0.0";
        EmbeddingServiceImpl zeroVector = service(request -> jsonResponse("""
                {"model":"text-embedding-3-small","data":[{"index":0,"embedding":[%s]}]}
                """.formatted(zeros)));
        assertThatThrownBy(() -> zeroVector.embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void decodesLargeRealHttpBatchResponseWithinBoundedBuffer() throws Exception {
        String body = largeBatchResponse();
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(256 * 1024)
                .isLessThan(2 * 1024 * 1024);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            WebClient webClient = new OpenAiWebClientConfig().openAiwebClient(WebClient.builder());
            EmbeddingServiceImpl service = new EmbeddingServiceImpl(webClient, settings("openai", "text-embedding-3-small", 1536, "http://127.0.0.1"));
            ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
            ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());

            List<List<Float>> vectors = service.embedBatch(java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> "batch input " + index).toList());

            assertThat(vectors).hasSize(32);
            assertThat(vectors).allSatisfy(vector -> assertThat(vector).hasSize(1536));
        } finally {
            server.stop(0);
        }
    }

    private String largeBatchResponse() {
        StringBuilder body = new StringBuilder("{\"model\":\"text-embedding-3-small\",\"data\":[");
        String vector = "1.1234567890123456," + "0.1234567890123456,".repeat(1534) + "0.1234567890123456";
        for (int index = 0; index < 32; index++) {
            if (index > 0) body.append(',');
            body.append("{\"index\":").append(index).append(",\"embedding\":[")
                    .append(vector).append("]}");
        }
        return body.append("],\"usage\":{\"prompt_tokens\":100,\"total_tokens\":100}}").toString();
    }

    private EmbeddingServiceImpl service(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(webClient, settings("openai", "text-embedding-3-small", 1536, "http://127.0.0.1"));
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.openai.test/");
        return service;
    }

    private EmbeddingSettings settings(String provider, String model, int dimensions, String baseUrl) {
        return new EmbeddingSettings(provider, model, dimensions, baseUrl);
    }

    private Mono<ClientResponse> response(String model, List<Integer> indices) {
        String rows = indices.stream().map(index -> {
            String vector = (index + 1) + ".0," + "0.0,".repeat(1534) + "0.0";
            return "{\"index\":%d,\"embedding\":[%s]}".formatted(index, vector);
        }).collect(java.util.stream.Collectors.joining(","));
        return jsonResponse("{\"model\":\"%s\",\"data\":[%s],\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"
                .formatted(model, rows));
    }

    private Mono<ClientResponse> localResponse(int count) {
        String vector = "1.0," + "0.0,".repeat(1022) + "0.0";
        String rows = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "{\"index\":%d,\"embedding\":[%s]}".formatted(index, vector))
                .collect(java.util.stream.Collectors.joining(","));
        return jsonResponse("{\"model\":\"%s\",\"data\":[%s]}"
                .formatted(EmbeddingSettings.KURE_MODEL, rows));
    }

    private Mono<ClientResponse> jsonResponse(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build());
    }
}
