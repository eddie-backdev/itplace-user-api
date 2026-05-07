package com.itplace.userapi.ai.llm.service;

import com.itplace.userapi.ai.llm.dto.response.CategoryResponse;
import com.itplace.userapi.ai.llm.dto.RecommendReason;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
