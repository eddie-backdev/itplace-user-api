package com.itplace.userapi.ai.llm.service;

import com.itplace.userapi.ai.llm.dto.response.CategoryResponse;
import com.itplace.userapi.ai.llm.dto.BenefitSelectionResponse;
import com.itplace.userapi.ai.llm.dto.RecommendReason;
import com.itplace.userapi.ai.question.intent.QueryIntent;
import com.itplace.userapi.recommend.dto.Candidate;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAIService {

    private static final double GPT_5_DEFAULT_TEMPERATURE = 1.0;
    private static final int CATEGORY_MAX_COMPLETION_TOKENS = 120;
    private static final int REASON_MAX_COMPLETION_TOKENS = 700;
    private static final int BENEFIT_SELECTION_MAX_COMPLETION_TOKENS = 350;
    private static final String LOW_REASONING_EFFORT = "low";

    private final OpenAiChatModel openAiChatModel;

    @Value("${spring.ai.chat.categorizePrompt}")
    private Resource categorizePromptRes;

    @Value("${spring.ai.chat.reasonPrompt}")
    private Resource reasonPromptRes;

    @Value("${spring.ai.openai.chat.model}")
    private String chatModel;

    private String categorizePrompt;
    private String reasonPrompt;


    @PostConstruct
    public void init() throws IOException {
        categorizePrompt = readPrompt(categorizePromptRes);
        reasonPrompt = readPrompt(reasonPromptRes);
    }

    private String readPrompt(Resource resource) throws IOException {
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public String generateReasons(String userInput, String category, List<String> partnerNames) {
        ChatClient chatClient = ChatClient.create(openAiChatModel);

        StringBuilder partnerList = new StringBuilder();
        for (String name : partnerNames) {
            partnerList.append("- ").append(name).append("\n");
        }

        String formattedPrompt = String.format(reasonPrompt, userInput, category, partnerList);

        return chatClient.prompt()
                .system(formattedPrompt)
                .options(chatOptions(REASON_MAX_COMPLETION_TOKENS))
                .user(userInput)
                .call()
                .entity(RecommendReason.class)
                .getReason();
    }

    public String categorize(String userInput) {
        ChatClient chatClient = ChatClient.create(openAiChatModel);

        CategoryResponse response = chatClient.prompt()
                .system(categorizePrompt)
                .options(chatOptions(CATEGORY_MAX_COMPLETION_TOKENS))
                .user(userInput)
                .call()
                .entity(CategoryResponse.class);

        return response.getCategory();
    }

    public List<Long> selectBenefitIds(String userInput,
                                       QueryIntent intent,
                                       List<Candidate> candidates,
                                       int maxSelections) {
        if (candidates == null || candidates.isEmpty() || maxSelections <= 0) {
            return List.of();
        }

        List<Candidate> selectableCandidates = candidates.stream()
                .filter(candidate -> candidate.getBenefitId() != null)
                .limit(30)
                .toList();
        if (selectableCandidates.isEmpty()) {
            return List.of();
        }

        Set<Long> allowedBenefitIds = selectableCandidates.stream()
                .map(Candidate::getBenefitId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        ChatClient chatClient = ChatClient.create(openAiChatModel);
        BenefitSelectionResponse response = chatClient.prompt()
                .system(benefitSelectionPrompt(userInput, intent, selectableCandidates, maxSelections))
                .options(chatOptions(BENEFIT_SELECTION_MAX_COMPLETION_TOKENS))
                .user(userInput)
                .call()
                .entity(BenefitSelectionResponse.class);

        if (response == null || response.getBenefitIds() == null) {
            return List.of();
        }

        return response.getBenefitIds().stream()
                .filter(allowedBenefitIds::contains)
                .distinct()
                .limit(maxSelections)
                .toList();
    }

    private String benefitSelectionPrompt(String userInput,
                                          QueryIntent intent,
                                          List<Candidate> candidates,
                                          int maxSelections) {
        String candidateBlock = candidates.stream()
                .map(this::candidateLine)
                .collect(Collectors.joining("\n"));
        String purpose = intent == null ? "" : String.join(", ", intent.purposeKeywords());
        String categoryHints = intent == null ? "" : String.join(", ", intent.categoryHints());
        String exclusions = intent == null ? "" : String.join(", ", intent.exclusions());
        String carrier = intent == null || intent.carrier() == null ? "" : intent.carrier().name();
        String grade = intent == null || intent.grade() == null ? "" : intent.grade().name();

        return """
                너는 ITPLACE 통신사 멤버십 혜택 후보 중에서 사용자 질문에 가장 적합한 혜택 ID만 고르는 재랭킹 엔진이다.

                [사용자 질문]
                %s

                [추출된 의도]
                - 목적: %s
                - 카테고리 힌트: %s
                - 제외 힌트: %s
                - 통신사: %s
                - 등급: %s

                [후보 혜택]
                %s

                ## 절대 규칙
                1. 후보 목록에 있는 benefitId만 선택한다.
                2. 최대 %d개까지만 선택한다.
                3. 사용자 질문의 상황과 혜택 문구, 제휴처 업종, 사용 맥락을 함께 보고 가장 직접적으로 맞는 혜택을 고른다.
                4. 질문 의도와 맞지 않는 후보는 semantic score가 높아도 제외한다.
                5. 제외 힌트와 충돌하는 후보는 선택하지 않는다.
                6. 새로운 제휴처명, 혜택명, benefitId를 만들지 않는다.
                7. JSON 외 문장, 코드블록, 주석은 출력하지 않는다.

                ## 출력 형식
                {
                  "benefitIds": [123, 456]
                }
                """.formatted(userInput, purpose, categoryHints, exclusions, carrier, grade, candidateBlock, maxSelections);
    }

    private String candidateLine(Candidate candidate) {
        return "- benefitId=%s | partner=%s | benefit=%s | category=%s | businessType=%s | useCases=%s | description=%s | context=%s | score=%s"
                .formatted(
                        candidate.getBenefitId(),
                        nullToBlank(candidate.getPartnerName()),
                        nullToBlank(candidate.getBenefitName()),
                        nullToBlank(candidate.getCategory()),
                        nullToBlank(candidate.getBusinessType()),
                        candidate.getUseCases() == null ? "" : String.join("/", candidate.getUseCases()),
                        nullToBlank(candidate.getDescription()),
                        nullToBlank(firstNonBlank(candidate.getOfflineContext(), candidate.getOnlineContext(), candidate.getContext())),
                        candidate.getSemanticScore() == null ? "" : candidate.getSemanticScore()
                );
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    OpenAiChatOptions chatOptions(int maxCompletionTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(chatModel)
                .temperature(GPT_5_DEFAULT_TEMPERATURE)
                .maxCompletionTokens(maxCompletionTokens);

        if (isGpt5Model(chatModel)) {
            builder.reasoningEffort(LOW_REASONING_EFFORT);
        }

        return builder.build();
    }

    private boolean isGpt5Model(String model) {
        return model != null && model.toLowerCase().startsWith("gpt-5");
    }

}
