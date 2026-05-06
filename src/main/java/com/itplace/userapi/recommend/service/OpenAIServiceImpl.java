package com.itplace.userapi.recommend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.ChatCompletionResponse;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAIServiceImpl implements OpenAIService {
    private static final int MAX_PROMPT_CANDIDATES = 15;
    private static final int MAX_RECOMMENDATION_COUNT = 5;
    private static final int MAX_DESCRIPTION_CHARS = 160;
    private static final int MAX_CONTEXT_CHARS = 180;
    private static final int MAX_COMPLETION_TOKENS = 700;
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(18);

    private final ObjectMapper mapper;
    @Qualifier("openAiWebClient")
    private final WebClient webClient;
    private final EmbeddingService embeddingService;
    private final BenefitSearchService benefitSearchService;
    private final PartnerRepository partnerRepository;
    private final BenefitRepository benefitRepository;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.model}")
    private String model;


    @Override
    public List<Candidate> vectorSearch(UserFeature uf, int CandidateSize) {
        List<Float> userEmbedding = embeddingService.embed(uf.getEmbeddingText());
        Grade grade = uf.getGrade();
        return benefitSearchService.queryVector(grade, userEmbedding, CandidateSize);
    }


    @Override
    public List<Recommendations> rerankAndExplain(UserFeature uf, List<Candidate> cands, int topK) {
        String url = baseUrl + "/v1/chat/completions";
        int recommendationCount = Math.min(Math.max(topK, 1), MAX_RECOMMENDATION_COUNT);
        List<Candidate> promptCandidates = cands.stream()
                .limit(MAX_PROMPT_CANDIDATES)
                .toList();

        log.info("LLM 추천 요청 후보 수: original={}, prompt={}, requestedTopK={}, actualTopK={}",
                cands.size(), promptCandidates.size(), topK, recommendationCount);

        StringBuilder items = new StringBuilder();
        for (int i = 0; i < promptCandidates.size(); i++) {
            Candidate c = promptCandidates.get(i);
            String description = truncateForPrompt(c.getDescription(), MAX_DESCRIPTION_CHARS, "설명 없음");
            String context = truncateForPrompt(c.getContext(), MAX_CONTEXT_CHARS, "추가 정보 없음");

            items.append(String.format(
                    "%d. [%s] 제휴처: %s / 설명: %s / 등급 혜택: %s%n",
                    i + 1,
                    nullToBlank(c.getCategory()),
                    nullToBlank(c.getPartnerName()),
                    description,
                    context
            ));
        }

        String prompt = String.format("""
                        【사용자 성향 요약】
                        %s
                        
                        【행동 로그 기반 관심 제휴사]
                        - 최근 클릭한 제휴사: %s
                        - 최근 검색한 제휴사: %s
                        - 최근 상세보기한 제휴사: %s
                        
                        【멤버십 혜택 이용 이력】
                        - 실제로 혜택을 자주 사용한 제휴사: %s
                        
                        【후보 혜택 목록】
                        %s
                        
                        ※ 아래 후보 혜택들 중 사용자에게 적절한 혜택 %d개를 골라주세요.
                        ※ 추천 이유에는 가능한 경우 등급 혜택과 최근 행동 로그 연관성을 짧게 포함해주세요.
                        ※ 추천 제휴처는 절대 중복되지 않도록 해주세요.
                        ※ 카테고리를 알 수 없는 경우, 카테고리에 관한 내용은 포함하지 마세요.
                        
                        Don't include markdown formatting. Just return valid JSON only.
                        {
                          "recommendations": [
                            {
                              "rank": 1,
                              "partnerName": "롯데시네마",
                              "reason": "최근 롯데시네마를 자주 클릭하셨더라구요! VIP 등급 혜택이 잘 맞는 걸요."
                            }
                          ]
                        }
                        """, uf.getLLMContext(),
                String.join(", ", uf.getClickPartners()),
                String.join(", ", uf.getSearchPartners()),
                String.join(", ", uf.getDetailPartners()),
                String.join(", ", uf.getRecentPartnerNames()),
                items,
                recommendationCount);

        List<Map<String, String>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", """
                                당신은 귀엽고 상냥한 우주 토끼 캐릭터 '잇콩'이에요!
                                사용자의 관심사와 혜택 정보를 바탕으로,
                                밝고 따뜻한 말투로 추천을 도와주는 안내 역할을 해요.
                                말투는 '~인 걸요!', '~했다구요!' 같은 어미를 자주 사용하세요.
                                """
                ),
                Map.of("role", "user", "content", prompt)
        );

        log.debug("추천 후보 리스트: {}", prompt);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("reasoning_effort", "low");

        long start = System.nanoTime();

        try {
            ChatCompletionResponse cr = webClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ChatCompletionResponse.class)
                    .block(LLM_TIMEOUT);

            long end = System.nanoTime();
            log.info("LLM 응답 생성 시간 (ms): {}", (end - start) / 1_000_000);

            if (cr != null && cr.getChoices() != null && !cr.getChoices().isEmpty()) {
                String jsonString = cr.getChoices().get(0).getMessage().getContent();
                JsonNode root = mapper.readTree(jsonString);
                JsonNode recList = root.get("recommendations");

                if (recList != null && recList.isArray()) {
                    List<Recommendations> recommendations = mapper.readerForListOf(Recommendations.class)
                            .readValue(recList);
                    return enrichRecommendations(recommendations);
                }
            }
        } catch (Exception e) {
            long end = System.nanoTime();
            log.warn("LLM 추천 생성 실패 또는 지연으로 기본 추천으로 대체합니다. elapsedMs={}, reason={}",
                    (end - start) / 1_000_000, e.getMessage());
        }

        return fallbackRecommendations(promptCandidates, recommendationCount);

    }

    private List<Recommendations> enrichRecommendations(List<Recommendations> recommendations) {
        for (Recommendations rec : recommendations) {
            Optional<Partner> partnerOpt = partnerRepository.findByPartnerName(rec.getPartnerName());
            if (partnerOpt.isPresent()) {
                Partner partner = partnerOpt.get();

                rec.setImgUrl(partner.getImage());

                List<Long> benefitIds = benefitRepository.findByPartner_PartnerId(partner.getPartnerId())
                        .stream()
                        .map(Benefit::getBenefitId)
                        .toList();

                rec.setBenefitIds(benefitIds);
            } else {
                rec.setImgUrl("<UNKNOWN>");
                rec.setBenefitIds(List.of());
            }
        }

        return recommendations;
    }

    private List<Recommendations> fallbackRecommendations(List<Candidate> candidates, int topK) {
        List<Recommendations> recommendations = candidates.stream()
                .filter(candidate -> candidate.getPartnerName() != null && !candidate.getPartnerName().isBlank())
                .limit(topK)
                .map(candidate -> Recommendations.builder()
                        .rank(candidates.indexOf(candidate) + 1)
                        .partnerName(candidate.getPartnerName())
                        .reason(String.format("%s 혜택이 지금 조건과 잘 맞는 걸요! %s",
                                candidate.getPartnerName(),
                                truncateForPrompt(candidate.getContext(), MAX_CONTEXT_CHARS, "등급별 혜택도 함께 확인해보세요.")))
                        .benefitIds(candidate.getBenefitId() == null ? List.of() : List.of(candidate.getBenefitId()))
                        .build())
                .toList();

        return enrichRecommendations(recommendations);
    }

    static String truncateForPrompt(String value, int maxChars, String defaultValue) {
        String normalized = value == null ? defaultValue : value.replaceAll("[\r\n]+", " ").trim();
        if (normalized.isBlank()) {
            normalized = defaultValue;
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "…";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
