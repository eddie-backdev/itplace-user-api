package com.itplace.userapi.ai.question.service;

import com.itplace.userapi.ai.forbiddenword.exception.ForbiddenWordException;
import com.itplace.userapi.ai.forbiddenword.service.ForbiddenWordService;
import com.itplace.userapi.ai.llm.dto.RecommendationResponse;
import com.itplace.userapi.ai.llm.service.LLMService;
import com.itplace.userapi.ai.question.QuestionCode;
import com.itplace.userapi.ai.question.exception.QuestionException;
import com.itplace.userapi.map.dto.StoreDetailDto;
import com.itplace.userapi.map.service.StoreService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuestionRecommendationServiceImpl implements QuestionRecommendationService {

    private final VectorStore vectorStore;                 // PgVector 기반 VectorStore만 사용
    private final StoreService storeService;
    private final LLMService llmService;
    private final ForbiddenWordService forbiddenWordService;

    @Override
    public RecommendationResponse recommendByQuestion(String question, double lat, double lng) {
        // 0) 금칙어 필터
        String censored = forbiddenWordService.censor(question);
        if (censored.contains("입력할 수 없는 단어")) {
            throw new ForbiddenWordException();
        }

        // 1) 유사 질문 검색 (임베딩 + pgvector 검색 자동)
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(1)
                        .build()
        );

        String category;
        if (hits.isEmpty()) {
            // 2-a) 매칭 결과가 없으면 LLM으로 카테고리 분류
            category = llmService.categorize(question);
            if (category == null || category.isBlank()) {
                throw new QuestionException(QuestionCode.NO_CATEGORY_FOUND);
            }
            // ※ 원한다면 여기서 CSV/DB에 새로 문서 추가해도 됨 (vectorStore.add)
            // vectorStore.add(List.of(new Document(question, Map.of("category", category))));
        } else {
            // 2-b) 매칭 결과의 메타데이터에서 카테고리 사용
            Document top = hits.get(0);
            Object cat = top.getMetadata().get("category");
            category = cat != null ? String.valueOf(cat) : null;
            if (category == null || category.isBlank()) {
                // 카테고리 메타데이터가 비어있으면 LLM fallback
                category = llmService.categorize(question);
                if (category == null || category.isBlank()) {
                    throw new QuestionException(QuestionCode.NO_CATEGORY_FOUND);
                }
            }
        }

        // 3) 제휴처 검색
        List<StoreDetailDto> stores = storeService.findNearbyByKeyword(lat, lng, null, category, 0, 0);
        if (stores.isEmpty()) {
            throw new QuestionException(QuestionCode.NO_STORE_FOUND);
        }

        List<String> partnerNames = stores.stream()
                .map(s -> s.getPartner().getPartnerName())
                .distinct()
                .limit(5)
                .toList();

        // 4) 추천 이유 생성
        String reason = llmService.generateReasons(question, category, partnerNames);

        // 5) 응답 조립
        var partners = partnerNames.stream()
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

        return RecommendationResponse.builder()
                .reason(reason)
                .partners(partners)
                .build();
    }
}
