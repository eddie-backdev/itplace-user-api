package com.itplace.userapi.ai.rag.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    public List<Float> embed(String text) {
        WebClient client = WebClient.builder()
                .baseUrl("https://api.openai.com/v1/embeddings")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> body = Map.of(
                "model", "text-embedding-3-small",
                "input", text,
                "encoding_format", "float"
        );

        Map response = client.post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<?> rawVector = (List<?>) ((Map<?, ?>) ((List<?>) response.get("data")).get(0)).get("embedding");

        List<Float> vector = rawVector.stream()
                .map(val -> ((Number) val).floatValue())
                .collect(Collectors.toList());

        return vector;
    }

}

