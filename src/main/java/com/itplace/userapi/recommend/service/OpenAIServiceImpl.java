package com.itplace.userapi.recommend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.ChatCompletionResponse;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.model}")
    private String model;


    @Override
    public List<Candidate> vectorSearch(UserFeature uf, int candidateSize) {
        List<Float> userEmbedding = embeddingService.embed(uf.getEmbeddingText());
        Carrier carrier = uf.getCarrier();
        Grade grade = uf.getGrade();
        return benefitSearchService.queryVector(carrier, grade, userEmbedding, candidateSize);
    }


    @Override
    public List<Recommendations> rerankAndExplain(UserFeature uf, List<Candidate> cands, int topK) {
        String url = baseUrl + "/v1/chat/completions";
        int recommendationCount = Math.min(Math.max(topK, 1), MAX_RECOMMENDATION_COUNT);
        List<Candidate> rankedCandidates = deterministicRank(uf, cands);
        List<Candidate> promptCandidates = rankedCandidates.stream()
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
                    "%d. 후보ID=%s / source=%s / rankScore=%.2f / 통신사=%s / 등급=%s / 이용=%s / [%s] 제휴처: %s / 설명: %s / 등급 혜택: %s / scoreEvidence=%s%n",
                    i + 1,
                    c.getBenefitId(),
                    nullToBlank(c.getCandidateSource()),
                    c.getRankScore() == null ? 0.0 : c.getRankScore(),
                    nullToBlank(c.getCarrier()),
                    nullToBlank(c.getGrade()),
                    nullToBlank(c.getUsageType()),
                    nullToBlank(c.getCategory()),
                    nullToBlank(c.getPartnerName()),
                    description,
                    context,
                    c.getScoreComponents() == null ? Map.of() : c.getScoreComponents()
            ));
        }

        String prompt = String.format("""
                        【사용자 성향 요약】
                        %s
                        
                        【행동 로그 기반 관심 제휴사]
                        - 최근 클릭한 제휴사: %s
                        - 최근 검색한 제휴사: %s
                        - 최근 상세보기한 제휴사: %s
                        
                        【종합 행동 기반 관심 제휴사】
                        - 클릭/검색/상세/즐겨찾기/이용 이력을 통합한 상위 제휴사: %s
                        - 노출되었지만 반응이 약한 제휴사: %s
                        - dismiss/skip/negative/favorite_remove 부정 신호 제휴사: %s
                        
                        【후보 혜택 목록】
                        %s
                        
                        ※ 아래 후보 혜택들 중 사용자에게 적절한 혜택 %d개를 골라주세요.
                        ※ 추천 이유에는 후보별 scoreEvidence에 있는 실제 신호와 등급 혜택만 짧게 포함해주세요.
                        ※ 부정 신호 또는 tombstone_penalty가 있는 제휴사는 다른 후보가 있으면 선택하지 마세요.
                        ※ scoreEvidence에 없는 즐겨찾기/사용/상세보기/검색/클릭 근거는 절대 만들지 마세요.
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
                String.join(", ", safeList(uf.getClickPartners())),
                String.join(", ", safeList(uf.getSearchPartners())),
                String.join(", ", safeList(uf.getDetailPartners())),
                String.join(", ", safeList(uf.getRecentPartnerNames())),
                String.join(", ", safeList(uf.getImpressionPartners())),
                negativePartnersSummary(uf),
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

        log.debug("추천 후보 프롬프트 구성 완료: promptCandidateCount={}, signalCounts(click/search/detail/use)={}/{}/{}/{}",
                promptCandidates.size(),
                safeList(uf.getClickPartners()).size(),
                safeList(uf.getSearchPartners()).size(),
                safeList(uf.getDetailPartners()).size(),
                safeList(uf.getRecentPartnerNames()).size());

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
                    return constrainRecommendationsToCandidates(recommendations, promptCandidates, recommendationCount);
                }
            }
        } catch (Exception e) {
            long end = System.nanoTime();
            log.warn("LLM 추천 생성 실패 또는 지연으로 기본 추천으로 대체합니다. elapsedMs={}, reason={}",
                    (end - start) / 1_000_000, e.getMessage());
        }

        return fallbackRecommendations(promptCandidates, recommendationCount);

    }

    List<Candidate> deterministicRank(UserFeature uf, List<Candidate> candidates) {
        return candidates.stream()
                .map(candidate -> applyRankScore(uf, candidate))
                .sorted(Comparator
                        .comparing((Candidate candidate) -> candidate.getRankScore() == null ? 0.0 : candidate.getRankScore())
                        .reversed()
                        .thenComparing(candidate -> candidate.getBenefitId() == null ? Long.MAX_VALUE : candidate.getBenefitId()))
                .toList();
    }

    private Candidate applyRankScore(UserFeature uf, Candidate candidate) {
        Map<String, Double> components = new LinkedHashMap<>();
        if (candidate.getScoreComponents() != null) {
            components.putAll(candidate.getScoreComponents());
        }

        double semantic = candidate.getSemanticScore() == null ? 0.0 : candidate.getSemanticScore();
        double behaviorAffinity = uf.partnerAffinity(candidate.getPartnerName());
        double categoryAffinity = uf.categoryAffinity(candidate.getCategory());
        double grounding = uf.hasSignalForPartner(candidate.getPartnerName()) ? 1.0 : 0.0;
        double negativePenalty = uf.negativePartnerScore(candidate.getPartnerName());
        double tombstonePenalty = uf.isTombstonedPartner(candidate.getPartnerName()) ? 50.0 : 0.0;
        double rankScore = (semantic * 20.0) + behaviorAffinity + (categoryAffinity * 0.5) + grounding
                - negativePenalty - tombstonePenalty;

        components.put("semantic_similarity", semantic);
        components.put("behavior_affinity", behaviorAffinity);
        components.put("category_affinity", categoryAffinity);
        components.put("grounded_user_signal", grounding);
        components.put("negative_partner_penalty", negativePenalty);
        if (tombstonePenalty > 0) {
            components.put("tombstone_penalty", tombstonePenalty);
        }

        candidate.setRankScore(rankScore);
        candidate.setScoreComponents(components);
        return candidate;
    }

    List<Recommendations> constrainRecommendationsToCandidates(List<Recommendations> recommendations,
                                                                  List<Candidate> promptCandidates,
                                                                  int topK) {
        Map<String, Candidate> allowedByPartner = new LinkedHashMap<>();
        for (Candidate candidate : promptCandidates) {
            String key = normalizePartnerName(candidate.getPartnerName());
            if (!key.isBlank()) {
                allowedByPartner.putIfAbsent(key, candidate);
            }
        }

        List<Recommendations> constrained = new ArrayList<>();
        Set<String> usedPartners = new HashSet<>();
        for (Recommendations recommendation : recommendations) {
            if (constrained.size() >= topK) {
                break;
            }

            String partnerKey = normalizePartnerName(recommendation.getPartnerName());
            Candidate candidate = allowedByPartner.get(partnerKey);
            if (candidate == null || usedPartners.contains(partnerKey)) {
                continue;
            }

            constrained.add(toRecommendation(candidate, constrained.size() + 1, recommendation.getReason()));
            usedPartners.add(partnerKey);
        }

        for (Candidate candidate : promptCandidates) {
            if (constrained.size() >= topK) {
                break;
            }
            String partnerKey = normalizePartnerName(candidate.getPartnerName());
            if (partnerKey.isBlank() || usedPartners.contains(partnerKey)) {
                continue;
            }

            constrained.add(toRecommendation(candidate, constrained.size() + 1, fallbackReason(candidate)));
            usedPartners.add(partnerKey);
        }

        return constrained;
    }

    private List<Recommendations> fallbackRecommendations(List<Candidate> candidates, int topK) {
        return constrainRecommendationsToCandidates(List.of(), candidates, topK);
    }

    private Recommendations toRecommendation(Candidate candidate, int rank, String reason) {
        return Recommendations.builder()
                .rank(rank)
                .partnerName(candidate.getPartnerName())
                .reason(reason == null || reason.isBlank() ? fallbackReason(candidate) : reason)
                .imgUrl(resolvePartnerImage(candidate.getPartnerName()))
                .benefitIds(candidate.getBenefitId() == null ? List.of() : List.of(candidate.getBenefitId()))
                .build();
    }

    private String fallbackReason(Candidate candidate) {
        return String.format("%s 혜택이 지금 조건과 잘 맞는 걸요! %s",
                candidate.getPartnerName(),
                truncateForPrompt(candidate.getContext(), MAX_CONTEXT_CHARS, "등급별 혜택도 함께 확인해보세요."));
    }

    private String resolvePartnerImage(String partnerName) {
        if (partnerRepository == null || partnerName == null || partnerName.isBlank()) {
            return null;
        }

        return partnerRepository.findByPartnerName(partnerName)
                .map(Partner::getImage)
                .orElse(null);
    }

    private String negativePartnersSummary(UserFeature uf) {
        if (uf.getNegativePartnerScores() == null || uf.getNegativePartnerScores().isEmpty()) {
            return "없음";
        }
        return uf.getNegativePartnerScores().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(7)
                .map(entry -> "%s %.1f".formatted(entry.getKey(), entry.getValue()))
                .toList()
                .toString();
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String normalizePartnerName(String partnerName) {
        return partnerName == null ? "" : partnerName.trim().toLowerCase();
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
