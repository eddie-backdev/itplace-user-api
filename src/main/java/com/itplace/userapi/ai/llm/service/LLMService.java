package com.itplace.userapi.ai.llm.service;

import com.itplace.userapi.ai.llm.dto.CategoryResponse;
import com.itplace.userapi.ai.llm.dto.RecommendReason;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LLMService {

    private final ChatClient chatClient;

    @Value("${spring.ai.chat.categorizePrompt}")
    private Resource categorizePromptRes;

    @Value("${spring.ai.chat.reasonPrompt}")
    private Resource reasonPromptRes;

    private String categorizePromptText;
    private String reasonPromptText;

    @PostConstruct
    public void init() throws IOException {
        categorizePromptText = readPrompt(categorizePromptRes);
        reasonPromptText = readPrompt(reasonPromptRes);

        if (categorizePromptText == null || categorizePromptText.isBlank()) {
            throw new IllegalStateException("categorizePrompt is empty or missing");
        }
        if (reasonPromptText == null || reasonPromptText.isBlank()) {
            throw new IllegalStateException("reasonPrompt is empty or missing");
        }
    }

    private String readPrompt(Resource resource) throws IOException {
        if (resource == null || !resource.exists()) {
            throw new IOException("Prompt resource missing: " + resource);
        }
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public String generateReasons(String userInput, String category, List<String> partnerNames) {
        Objects.requireNonNull(userInput, "userInput");
        Objects.requireNonNull(category, "category");

        String partners = (partnerNames == null || partnerNames.isEmpty())
                ? "없음"
                : String.join("\n", partnerNames);

        // system에는 규칙/형식만. 변수 주입은 template로.
        PromptTemplate systemTemplate = new PromptTemplate(reasonPromptText);
        Prompt systemPrompt = systemTemplate.create(
                java.util.Map.of(
                        "category", category,
                        "partners", partners
                )
        );

        RecommendReason entity = chatClient.prompt()
                .system(systemPrompt.getContents()) // 규칙/출력형식
                .user(userInput)                    // 실제 입력은 user 메시지로
                .call()
                .entity(RecommendReason.class);

        if (entity == null || entity.getReason() == null) {
            // 형식이 어긋난 경우, content로 받아 재시도하는 fallback
            String content = chatClient.prompt()
                    .system(systemPrompt.getContents())
                    .user("다시 JSON 형식으로 이유만 생성해줘. 입력: " + userInput)
                    .call()
                    .content();
            // 간단 파싱 혹은 그대로 반환
            return content;
        }
        return entity.getReason();
    }

    public String categorize(String userInput) {
        Objects.requireNonNull(userInput, "userInput");

        PromptTemplate systemTemplate = new PromptTemplate(categorizePromptText);
        Prompt systemPrompt = systemTemplate.create(java.util.Map.of());

        CategoryResponse response = chatClient.prompt()
                .system(systemPrompt.getContents())
                .user(userInput)
                .call()
                .entity(CategoryResponse.class);

        if (response == null || response.getCategory() == null) {
            String fallback = chatClient.prompt()
                    .system(systemPrompt.getContents())
                    .user("입력을 한 단일 카테고리로만 JSON 없이 한 단어로 답해줘: " + userInput)
                    .call()
                    .content();
            return fallback.trim();
        }
        return response.getCategory();
    }
}
