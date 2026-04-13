package com.itplace.userapi.ai.question.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.itplace.userapi.ai.llm.dto.response.RecommendationResponse;
import com.itplace.userapi.ai.question.QuestionCode;
import com.itplace.userapi.ai.question.service.QuestionRecommendationService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.common.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/questions")
@Validated
public class QuestionSearchController {

    private final EmbeddingService embeddingService;
    private final ElasticsearchClient elasticsearchClient;
    private final QuestionRecommendationService questionRecommendationService;

    @GetMapping("/search")
    public Map<String, Object> searchSimilarQuestion(
            @RequestParam @NotBlank @Size(max = 200) String query) {
        List<Float> embedding = embeddingService.embed(query);

        SearchResponse<Map> response;
        try {
            response = elasticsearchClient.search(s -> s
                            .index("questions")
                            .knn(k -> k
                                    .field("embedding")
                                    .k(1)
                                    .numCandidates(10)
                                    .queryVector(embedding)
                            ),
                    Map.class
            );
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch 검색 실패", e);
        }

        List<Hit<Map>> hits = response.hits().hits();
        if (hits.isEmpty()) {
            return Map.of("message", "No similar question found");
        }

        Map<String, Object> topHit = hits.get(0).source();
        return Map.of(
                "matchedQuestion", topHit.get("question"),
                "category", topHit.get("category"),
                "score", hits.get(0).score()
        );
    }

    @GetMapping("/recommend")
    public ResponseEntity<ApiResponse<RecommendationResponse>> recommend(
            @RequestParam @NotBlank @Size(max = 200) String question,
            @RequestParam double lat,
            @RequestParam double lng) {

        RecommendationResponse result = questionRecommendationService.recommendByQuestion(question, lat, lng);
        ApiResponse<RecommendationResponse> body = ApiResponse.of(QuestionCode.QUESTION_SUCCESS, result);
        return ResponseEntity.status(body.getStatus()).body(body);
    }
}
