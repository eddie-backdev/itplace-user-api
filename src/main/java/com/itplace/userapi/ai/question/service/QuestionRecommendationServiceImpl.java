package com.itplace.userapi.ai.question.service;

import com.itplace.userapi.ai.forbiddenword.exception.ForbiddenWordException;
import com.itplace.userapi.ai.forbiddenword.service.ForbiddenWordService;
import com.itplace.userapi.ai.llm.dto.response.RecommendationResponse;
import com.itplace.userapi.ai.llm.service.OpenAIService;
import com.itplace.userapi.ai.question.QuestionCode;
import com.itplace.userapi.ai.question.exception.QuestionException;
import com.itplace.userapi.ai.question.guard.BenefitCandidateGuard;
import com.itplace.userapi.ai.question.guard.BenefitCandidateGuard.GuardResult;
import com.itplace.userapi.ai.question.intent.QueryIntent;
import com.itplace.userapi.ai.question.intent.QueryIntentExtractor;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionRecommendationServiceImpl implements QuestionRecommendationService {
    private static final int MAX_PARTNER_CANDIDATES = 5;
    private static final int BENEFIT_RETRIEVAL_CANDIDATES = 30;
    private final EmbeddingService embeddingService;
    private final StoreService storeService;
    private final OpenAIService openAIService;
    private final ForbiddenWordService forbiddenWordService;
    private final BenefitSearchService benefitSearchService;
    private final QueryIntentExtractor queryIntentExtractor;
    private final BenefitCandidateGuard benefitCandidateGuard;

    @Override
    public RecommendationResponse recommendByQuestion(String question, double lat, double lng) {
        return recommendByQuestion(question, lat, lng, null, null);
    }

    @Override
    public RecommendationResponse recommendByQuestion(String question, double lat, double lng, Carrier carrier, Grade grade) {
        // 0. 금칙어 필터링
        String result = forbiddenWordService.censor(question);
        if (result.contains("입력할 수 없는 단어")) {
            throw new ForbiddenWordException();
        }

        QueryIntent intent = queryIntentExtractor.extract(question, carrier, grade, lat, lng);

        // 1. 사용자 질문 + 의도 키워드 임베딩
        List<Float> embedding;
        try {
            embedding = embeddingService.embed(intent.retrievalText());
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("OpenAI 임베딩 인증 실패: status={}", e.getStatusCode());
            throw new QuestionException(QuestionCode.AI_SERVICE_UNAVAILABLE);
        } catch (WebClientResponseException e) {
            log.warn("OpenAI 임베딩 호출 실패: status={}", e.getStatusCode());
            throw new QuestionException(QuestionCode.AI_SERVICE_UNAVAILABLE);
        }

        // 2. 운영 추천 경로는 questions 인덱스/CSV memory를 사용하지 않고 Benefit RAG 후보만 조회한다.
        List<Candidate> retrievedCandidates = benefitSearchService.queryVector(
                intent.carrier(),
                intent.grade(),
                embedding,
                BENEFIT_RETRIEVAL_CANDIDATES
        );
        GuardResult guardResult = benefitCandidateGuard.filter(intent, retrievedCandidates);
        StoreCandidateResult storeCandidateResult = findStoresForBenefitCandidates(guardResult.accepted(), lat, lng);
        if (storeCandidateResult.stores().isEmpty()) {
            log.debug("질문형 추천 후보 없음: traceId={}, intentConfidence={}, carrier={}, grade={}, retrieved={}, guarded={}, rejected={}, locationContext={}",
                    intent.traceId(),
                    intent.confidence(),
                    intent.carrier(),
                    intent.grade(),
                    retrievedCandidates.size(),
                    guardResult.accepted().size(),
                    guardResult.rejectedCount(),
                    intent.locationContext());
            throw new QuestionException(QuestionCode.NO_STORE_FOUND);
        }

        log.debug("질문형 추천 Benefit RAG 경로 사용: traceId={}, intentConfidence={}, carrier={}, grade={}, retrieved={}, guarded={}, rejected={}, retrievalSource={}",
                intent.traceId(),
                intent.confidence(),
                intent.carrier(),
                intent.grade(),
                retrievedCandidates.size(),
                guardResult.accepted().size(),
                guardResult.rejectedCount(),
                storeCandidateResult.source());
        return buildRecommendationResponse(question, intent, storeCandidateResult);
    }

    private RecommendationResponse buildRecommendationResponse(String question,
                                                               QueryIntent intent,
                                                               StoreCandidateResult storeCandidateResult) {
        String category = storeCandidateResult.category();
        List<StoreDetailResponse> stores = storeCandidateResult.stores();
        List<String> partnerNames = stores.stream()
                .map(s -> s.getPartner().getPartnerName())
                .distinct()
                .limit(MAX_PARTNER_CANDIDATES)
                .toList();

        // 5. 추천 이유 생성: 질문형 추천은 후보/의도를 벗어난 LLM 추론을 금지하고
        // 실제 반환 후보와 추출된 의도만으로 결정론적 근거를 만든다.
        String reason = groundedReason(question, intent, category, partnerNames);

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

    private String groundedReason(String question, QueryIntent intent, String category, List<String> partnerNames) {
        String normalizedCategory = category == null || category.isBlank() ? "요청하신 조건" : category;
        if (partnerNames == null || partnerNames.isEmpty()) {
            return "%s에 맞는 주변 제휴처를 찾아봤어요.".formatted(normalizedCategory);
        }

        String joinedPartners = String.join(", ", partnerNames);
        if (intent != null && intent.purposeKeywords().stream().anyMatch(keyword -> keyword.contains("더위") || keyword.contains("시원"))) {
            return "더운 날씨에는 오래 머물기 좋은 실내 공간이나 차가운 음료·디저트와 연결되는 혜택이 중요해서, "
                    + normalizedCategory + " 의도와 맞는 후보 중 " + joinedPartners + " 위주로 골랐어요.";
        }
        if (intent != null && intent.purposeKeywords().stream().anyMatch(keyword -> keyword.contains("음료") || keyword.contains("카페"))) {
            return "음료나 가벼운 디저트를 이용하기 좋은 제휴처를 우선해, "
                    + normalizedCategory + " 의도와 맞는 후보 중 " + joinedPartners + " 위주로 추천드려요.";
        }
        return "요청하신 “%s” 의도에 맞춰 %s 후보 중 %s 제휴처를 추천드려요."
                .formatted(question, normalizedCategory, joinedPartners);
    }

    private StoreCandidateResult findStoresForBenefitCandidates(List<Candidate> benefitCandidates,
                                                                double lat,
                                                                double lng) {
        if (benefitCandidates == null || benefitCandidates.isEmpty()) {
            return new StoreCandidateResult(null, List.of(), "benefit_rag");
        }

        List<StoreDetailResponse> mergedStores = new ArrayList<>();
        String selectedCategory = benefitCandidates.stream()
                .map(Candidate::getCategory)
                .filter(category -> category != null && !category.isBlank())
                .findFirst()
                .orElse("혜택");
        LinkedHashSet<String> partnerNames = benefitCandidates.stream()
                .map(Candidate::getPartnerName)
                .filter(partnerName -> partnerName != null && !partnerName.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (String partnerName : partnerNames) {
            try {
                List<StoreDetailResponse> stores = storeService.findNearbyByPartnerName(lat, lng, partnerName, lat, lng);
                if (!stores.isEmpty()) {
                    mergedStores.addAll(stores);
                }
            } catch (RuntimeException e) {
                log.debug("질문형 Benefit RAG 후보의 주변 매장 조회를 건너뜁니다. partnerName={}, reason={}",
                        partnerName, e.getMessage());
            }

            if (mergedStores.stream().map(store -> store.getPartner().getPartnerName()).distinct().count() >= MAX_PARTNER_CANDIDATES) {
                break;
            }
        }
        String source = benefitCandidates.get(0).getCandidateSource();
        return new StoreCandidateResult(selectedCategory, mergedStores,
                source == null || source.isBlank() ? "benefit_rag" : source);
    }

    private record StoreCandidateResult(String category, List<StoreDetailResponse> stores, String source) {
    }
}
