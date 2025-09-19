package com.itplace.userapi.ai.question.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.itplace.userapi.ai.forbiddenword.exception.ForbiddenWordException;
import com.itplace.userapi.ai.forbiddenword.service.ForbiddenWordService;
import com.itplace.userapi.ai.llm.dto.RecommendationResponse;
import com.itplace.userapi.ai.llm.service.OpenAIService;
import com.itplace.userapi.ai.question.QuestionCode;
import com.itplace.userapi.ai.question.exception.QuestionException;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.map.dto.StoreDetailDto;
import com.itplace.userapi.map.service.StoreService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuestionRecommendationServiceImpl implements QuestionRecommendationService {
    private final EmbeddingService embeddingService;
    private final ElasticsearchClient esClient;
    private final StoreService storeService;
    private final OpenAIService openAIService;
    private final ElasticQuestionService elasticQuestionService;
    private final ForbiddenWordService forbiddenWordService;


    public RecommendationResponse recommendByQuestion(String question, double lat, double lng) throws Exception {
        // 0. 금칙어 필터링
        String result = forbiddenWordService.censor(question);
        if (result.contains("입력할 수 없는 단어")) {
            throw new ForbiddenWordException();

        }
        // 1. 사용자 질문 임베딩
        List<Float> embedding = embeddingService.embed(question);

        // 2. ES에서 top1 검색
        SearchResponse<Map> response = esClient.search(s -> s
                        .index("questions")
                        .knn(knn -> knn
                                .field("embedding")
                                .k(1)
                                .numCandidates(10)
                                .queryVector(embedding)
                        ),
                Map.class
        );

        List<Hit<Map>> hits = response.hits().hits();

        String category;

        if (hits.isEmpty() || (hits.get(0).score() != null && hits.get(0).score() < 0.7)) {
            // 3. score가 낮거나 검색 실패한 경우 → LLM으로 카테고리 분류
            category = openAIService.categorize(question);
            // LLM이 카테고리를 못 준 경우 처리
            if (category == null || category.isBlank()) {
                throw new QuestionException(QuestionCode.NO_CATEGORY_FOUND);
            }
            // 질문 + LLM 카테고리 + 임베딩 결과를 ES에 저장
            elasticQuestionService.saveQuestion(
                    "questions",
                    UUID.randomUUID().toString(),
                    question,
                    category,
                    embedding
            );
        } else {
            // 3-2. score가 높으면 ES 결과에서 카테고리 추출
            category = (String) hits.get(0).source().get("category");
        }

        System.out.println("카테고리 분류: " + category);
        // 4. 제휴처 목록 조회
        List<StoreDetailDto> stores = storeService.findNearbyByKeyword(lat, lng, null, category, 0, 0);

        if (stores.isEmpty()) {
            throw new QuestionException(QuestionCode.NO_STORE_FOUND);
        }

        List<String> partnerNames = stores.stream()
                .map(s -> s.getPartner().getPartnerName())
                .distinct()
                .limit(5)
                .toList();

        // 5. 추천 이유 생성
        String reason = openAIService.generateReasons(question, category, partnerNames);

        // 6. partnerName + imgUrl 조립
        List<RecommendationResponse.PartnerSummary> partners = partnerNames.stream()
                .map(name -> {
                    String imgUrl = stores.stream()
                            .filter(s -> s.getPartner().getPartnerName().equals(name))
                            .findFirst()
                            .map(s -> s.getPartner().getImage())
                            .orElse(null);
                    return RecommendationResponse.PartnerSummary.builder()
                            .partnerName(name)
                            .imgUrl(imgUrl)
                            .build();
                }).toList();

        // 최종 응답 조립
        return RecommendationResponse.builder()
                .reason(reason)
                .partners(partners)
                .build();
    }
}