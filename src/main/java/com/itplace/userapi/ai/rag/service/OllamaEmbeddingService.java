package com.itplace.userapi.ai.rag.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.ai.embedding", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingService implements EmbeddingService {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_BATCH_SIZE = 3;
    private static final String QUERY_PREFIX = "Instruct: Given a Korean telecom membership benefit query, retrieve relevant benefit descriptions.\nQuery: ";

    private final WebClient webClient;
    private final EmbeddingSettings settings;

    public OllamaEmbeddingService(WebClient.Builder builder, EmbeddingSettings settings) {
        this.webClient = builder.clone().baseUrl(trimTrailingSlash(settings.baseUrl())).build();
        this.settings = settings;
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input must not be blank");
        validateInputs(List.of(text));
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<Float> embedQuery(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input must not be blank");
        return embedBatch(List.of(QUERY_PREFIX + text), QUERY_TIMEOUT).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        validateInputs(texts);
        if (texts.isEmpty()) return List.of();
        return embedBatch(texts, TIMEOUT);
    }

    private List<List<Float>> embedBatch(List<String> texts, Duration timeout) {
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH_SIZE, texts.size()));
            vectors.addAll(requestBatch(batch, timeout));
        }
        return List.copyOf(vectors);
    }

    private List<List<Float>> requestBatch(List<String> texts, Duration timeout) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", settings.model());
        body.put("input", texts);
        body.put("truncate", false);
        body.put("keep_alive", "5m");
        body.put("options", Map.of("num_ctx", 8192));

        Map<?, ?> response = webClient.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(this::isRetryable))
                .block(timeout);

        if (response == null) throw new IllegalStateException("Ollama embedding response is empty");
        if (!settings.model().equals(response.get("model"))) {
            throw new IllegalStateException("Ollama embedding response model does not match configuration");
        }
        Object rawEmbeddings = response.get("embeddings");
        if (!(rawEmbeddings instanceof List<?> rows) || rows.size() != texts.size()) {
            throw new IllegalStateException("Ollama embedding response count does not match request");
        }
        return rows.stream().map(this::validatedVector).toList();
    }

    private List<Float> validatedVector(Object rawVector) {
        if (!(rawVector instanceof List<?> values) || values.size() != settings.dimensions()) {
            throw new IllegalStateException("Ollama embedding vector dimension does not match configuration");
        }
        List<Float> vector = new ArrayList<>(values.size());
        boolean nonZero = false;
        for (Object value : values) {
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Ollama embedding vector contains a non-numeric value");
            }
            float component = number.floatValue();
            if (!Float.isFinite(component)) {
                throw new IllegalStateException("Ollama embedding vector contains a non-finite value");
            }
            nonZero |= component != 0f;
            vector.add(component);
        }
        if (!nonZero) throw new IllegalStateException("Ollama embedding vector must not be all zero");
        return List.copyOf(vector);
    }

    private void validateInputs(List<String> texts) {
        if (texts == null) throw new IllegalArgumentException("Embedding inputs are required");
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank");
        }
    }

    private boolean isRetryable(Throwable failure) {
        if (!(failure instanceof WebClientResponseException responseException)) return false;
        int status = responseException.getStatusCode().value();
        return status == 429 || status >= 500;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
