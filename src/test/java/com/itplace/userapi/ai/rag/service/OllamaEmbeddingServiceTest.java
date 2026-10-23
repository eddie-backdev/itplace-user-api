package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OllamaEmbeddingServiceTest {

    @Test
    void prefixesOnlyQueriesAndSendsSafeNativeEmbedOptions() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        withServer(exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response("qwen3-embedding:4b-q8_0", 1, true));
        }, server -> {
            OllamaEmbeddingService service = service(server);

            assertThat(service.embedBatch(List.of("document text"))).hasSize(1);
            assertThat(service.embedQuery("coffee discount")).hasSize(2560);
        });

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).contains("\"input\":[\"document text\"]");
        assertThat(requests.get(1)).contains("Instruct: Given a Korean telecom membership benefit query, retrieve relevant benefit descriptions.\\nQuery: coffee discount");
        assertThat(requests).allSatisfy(body -> assertThat(body)
                .contains("\"truncate\":false", "\"keep_alive\":\"5m\"", "\"num_ctx\":8192"));
    }

    @Test
    void rejectsWrongDimensionAndWrongResponseCount() throws Exception {
        withServer(exchange -> respond(exchange, 200, response("qwen3-embedding:4b-q8_0", 1, false)), server ->
                assertThatThrownBy(() -> service(server).embed("text"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("dimension"));

        withServer(exchange -> respond(exchange, 200, "{\"model\":\"qwen3-embedding:4b-q8_0\",\"embeddings\":[]}"), server ->
                assertThatThrownBy(() -> service(server).embed("text"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("count"));
    }

    @Test
    void retriesTransientServerFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        withServer(exchange -> {
            if (attempts.getAndIncrement() == 0) respond(exchange, 503, "{}");
            else respond(exchange, 200, response("qwen3-embedding:4b-q8_0", 1, true));
        }, server -> assertThat(service(server).embed("text")).hasSize(2560));
        assertThat(attempts).hasValue(2);
    }

    private OllamaEmbeddingService service(HttpServer server) {
        return new OllamaEmbeddingService(WebClient.builder(),
                new EmbeddingSettings("ollama", "qwen3-embedding:4b-q8_0", 2560,
                        "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private String response(String model, int count, boolean correctDimensions) {
        int dimensions = correctDimensions ? 2560 : 1536;
        String vector = "0.5," + "0.0,".repeat(dimensions - 2) + "0.0";
        String rows = java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> "[" + vector + "]")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"model\":\"" + model + "\",\"embeddings\":[" + rows + "]}";
    }

    private void withServer(ExchangeHandler handler, java.util.function.Consumer<HttpServer> test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", handler::handle);
        server.start();
        try {
            test.accept(server);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws java.io.IOException;
    }
}
