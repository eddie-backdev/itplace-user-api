package com.itplace.userapi.ai.rag.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(8);

    @Qualifier("openAiWebClient")
    private final WebClient webClient;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.embedding.model:text-embedding-3-small}")
    private String embeddingModel;

    public List<Float> embed(String text) {
        Map<String, Object> body = Map.of(
                "model", embeddingModel,
                "input", text,
                "encoding_format", "float"
        );

        Map response = webClient.post()
                .uri(openAiEmbeddingsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(EMBEDDING_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("OpenAI embedding response is empty");
        }

        List<?> rawVector = (List<?>) ((Map<?, ?>) ((List<?>) response.get("data")).get(0)).get("embedding");

        return rawVector.stream()
                .map(val -> ((Number) val).floatValue())
                .collect(Collectors.toList());
    }

    private String openAiEmbeddingsUrl() {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBaseUrl + "/v1/embeddings";
    }

}
