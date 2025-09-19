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
import com.itplace.userapi.recommend.dto.Recommendations;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class OpenAIServiceImpl implements OpenAIService {
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

        System.out.println("후보 사이즈: " + cands.size());
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            Candidate c = cands.get(i);
            String description = c.getDescription() != null ? c.getDescription().replaceAll("[\\r\\n]+", " ") : "설명 없음";
            String context = c.getContext() != null ? c.getContext().replaceAll("[\\r\\n]+", " ") : "추가 정보 없음";

            items.append(String.format(
                    "%d. [%s] 제휴처: %s / 설명: %s / 등급 혜택: %s\n",
                    i + 1,
                    c.getCategory(),
                    c.getPartnerName(),
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
                        
                        ※ 아래 후보 혜택들 중 사용자에게 적절한 혜택 %d개 이상을 골라주세요.
                        
                        ※ 반드시 다음 조건을 지켜주세요:
                        - 추천 이유에는 **반드시 등급 혜택**을 포함해주세요.
                          - 단, '설명 없음' 또는 '추가 정보 없음'인 경우 제외해도 됩니다.
                        - 그리고 아래 중 하나 이상을 추가로 포함해야 합니다:
                          - 사용자가 최근 행동 로그(click/search/detail)에서 본 제휴사와의 연관성
                        - 예: "최근 CGV를 자주 클릭하셨더라구요!", "실제로 VIPS 혜택을 많이 사용하셨네요!"
                        - 추천 제휴처는 절대 중복되지 않도록 해주세요.
                        - 카테고리를 알 수 없는 경우, 카테고리에 관한 내용은 포함하지 마세요.
                        
                        
                        "Don't include markdown formatting. Just return valid JSON only."
                        {
                          "recommendations": [
                            {
                              "rank": 1,
                              "partnerName": "롯데시네마",
                              "reason": "최근 롯데시네마를 자주 클릭하셨더라구요! 문화/여가 혜택 중 이 혜택이 특히 잘 맞을 것 같아요."
                            },
                            {
                              "rank": 2,
                              "partnerName": "GS25",
                              "reason": "GS25에 특히 관심이 많으시니, 일상에서 유용하게 쓰실 수 있겠어요! 1천원 당 100원 할인받으실 수 있다구요!"
                            }
                          ]
                        }
                        """, uf.getLLMContext(),
                String.join(", ", uf.getClickPartners()),
                String.join(", ", uf.getSearchPartners()),
                String.join(", ", uf.getDetailPartners()),
                String.join(", ", uf.getRecentPartnerNames()),
                items,
                topK);

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

        System.out.println("추천 후보 리스트: " + prompt);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages
        );

        long start = System.nanoTime();

        ChatCompletionResponse cr = webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .block();

        long end = System.nanoTime();
        System.out.println("LLM 응답 생성 시간 (ms): " + (end - start) / 1_000_000);

        if (cr != null && !cr.getChoices().isEmpty()) {
            String jsonString = cr.getChoices().get(0).getMessage().getContent();
            try {
                JsonNode root = mapper.readTree(jsonString);
                JsonNode recList = root.get("recommendations");

                List<Recommendations> recommendations = mapper.readerForListOf(Recommendations.class)
                        .readValue(recList);

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
            } catch (Exception e) {
                throw new RuntimeException("LLM 추천 결과 파싱 실패", e);
            }
        }
        return List.of();

    }
}