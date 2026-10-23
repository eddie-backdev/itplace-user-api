package com.itplace.userapi.ai.rag.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.ai.embedding.provider:openai}' == 'openai' or '${app.ai.embedding.provider:openai}' == 'local'")
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(8);
    private static final int OPENAI_MAX_BATCH_SIZE = 32;
    private static final int LOCAL_MAX_BATCH_SIZE = 4;
    private static final Duration LOCAL_BULK_TIMEOUT = Duration.ofSeconds(60);
    @Qualifier("openAiWebClient")
    private final WebClient webClient;
    private final EmbeddingSettings settings;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com/}")
    private String baseUrl;

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input must not be blank");
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<Float> embedQuery(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input must not be blank");
        return requestBatch(List.of(text), EMBEDDING_TIMEOUT).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null) throw new IllegalArgumentException("Embedding inputs are required");
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank");
        }
        if (texts.isEmpty()) return List.of();

        List<List<Float>> vectors = new ArrayList<>(texts.size());
        int batchSize = "local".equals(settings.provider()) ? LOCAL_MAX_BATCH_SIZE : OPENAI_MAX_BATCH_SIZE;
        Duration timeout = "local".equals(settings.provider()) ? LOCAL_BULK_TIMEOUT : EMBEDDING_TIMEOUT;
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            vectors.addAll(requestBatch(batch, timeout));
        }
        return List.copyOf(vectors);
    }

    private List<List<Float>> requestBatch(List<String> texts, Duration timeout) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", settings.model());
        body.put("input", texts);
        body.put("encoding_format", "float");
        if ("local".equals(settings.provider())) body.put("dimensions", settings.dimensions());
        else if (settings.model().startsWith("text-embedding-3-")) body.put("dimensions", settings.dimensions());
        var request = webClient.post().uri(openAiEmbeddingsUrl());
        if ("openai".equals(settings.provider())) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        Map<?, ?> response = request.bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(150))
                        .maxBackoff(Duration.ofSeconds(1))
                        .filter(this::isRetryable))
                .block(timeout);

        if (response == null) throw new IllegalStateException("OpenAI embedding response is empty");
        logUsage(response.get("usage"), texts.size());
        if (!settings.model().equals(response.get("model"))) {
            throw new IllegalStateException("OpenAI embedding response model does not match configuration");
        }
        Object rawData = response.get("data");
        if (!(rawData instanceof List<?> rows) || rows.size() != texts.size()) {
            throw new IllegalStateException("OpenAI embedding response count does not match request");
        }

        List<Map<?, ?>> indexedRows = new ArrayList<>(rows.size());
        Set<Integer> seenIndices = new HashSet<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map) || !(map.get("index") instanceof Number indexValue)) {
                throw new IllegalStateException("OpenAI embedding response has an invalid index");
            }
            int index = indexValue.intValue();
            if (indexValue.doubleValue() != index || index < 0 || index >= texts.size() || !seenIndices.add(index)) {
                throw new IllegalStateException("OpenAI embedding response has a duplicate or out-of-range index");
            }
            indexedRows.add(map);
        }
        if (seenIndices.size() != texts.size()) {
            throw new IllegalStateException("OpenAI embedding response is missing an index");
        }

        List<Map<?, ?>> orderedRows = indexedRows.stream()
                .sorted(Comparator.comparingInt(row -> ((Number) row.get("index")).intValue()))
                .toList();
        return orderedRows.stream().map(this::validatedVector).toList();
    }

    private List<Float> validatedVector(Map<?, ?> row) {
        Object rawEmbedding = row.get("embedding");
        if (!(rawEmbedding instanceof List<?> values) || values.size() != settings.dimensions()) {
            throw new IllegalStateException("OpenAI embedding vector must have " + settings.dimensions() + " dimensions");
        }
        List<Float> vector = new ArrayList<>(values.size());
        boolean nonZero = false;
        for (Object value : values) {
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("OpenAI embedding vector contains a non-numeric value");
            }
            float component = number.floatValue();
            if (!Float.isFinite(component)) {
                throw new IllegalStateException("OpenAI embedding vector contains a non-finite value");
            }
            nonZero |= component != 0f;
            vector.add(component);
        }
        if (!nonZero) throw new IllegalStateException("OpenAI embedding vector must not be all zero");
        return List.copyOf(vector);
    }

    private void logUsage(Object rawUsage, int inputCount) {
        if (!(rawUsage instanceof Map<?, ?> usage)) return;
        Object promptTokens = tokenCount(usage.get("prompt_tokens"));
        Object totalTokens = tokenCount(usage.get("total_tokens"));
        log.info("Embedding batch completed model={} inputs={} promptTokens={} totalTokens={}",
                settings.model(), inputCount, promptTokens, totalTokens);
    }

    private Long tokenCount(Object value) {
        return value instanceof Number number && number.longValue() >= 0 ? number.longValue() : null;
    }

    private boolean isRetryable(Throwable failure) {
        if (!(failure instanceof WebClientResponseException responseException)) return false;
        int status = responseException.getStatusCode().value();
        return status == 429 || status >= 500;
    }

    private String openAiEmbeddingsUrl() {
        String configuredBaseUrl = "local".equals(settings.provider()) ? settings.baseUrl() : baseUrl;
        String normalizedBaseUrl = configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1) : configuredBaseUrl;
        return normalizedBaseUrl + "/v1/embeddings";
    }
}
