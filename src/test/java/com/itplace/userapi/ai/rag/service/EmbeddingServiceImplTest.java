package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class EmbeddingServiceImplTest {

    @Test
    void embed_usesSharedOpenAiWebClientAndConfiguredEndpoint() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("""
                            {
                              "data": [
                                {
                                  "embedding": [0.1, 0.2, 0.3]
                                }
                              ]
                            }
                            """)
                    .build());
        };
        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        EmbeddingServiceImpl embeddingService = new EmbeddingServiceImpl(webClient);
        ReflectionTestUtils.setField(embeddingService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(embeddingService, "baseUrl", "https://api.openai.test/");
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", "text-embedding-3-small");

        List<Float> vector = embeddingService.embed("검색어");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(capturedRequest.get().url().toString()).isEqualTo("https://api.openai.test/v1/embeddings");
        assertThat(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer test-api-key");
    }
}
