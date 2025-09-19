package com.itplace.userapi.ai.llm.service;

import com.itplace.userapi.ai.llm.dto.CategoryResponse;
import com.itplace.userapi.ai.llm.dto.RecommendReason;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAIService {

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
                .options(ChatOptions.builder()
                        .model(chatModel)
                        .temperature(0.7)
                        .build())
                .user(userInput)
                .call()
                .entity(RecommendReason.class)
                .getReason();
    }

    public String categorize(String userInput) {
        ChatClient chatClient = ChatClient.create(openAiChatModel);

        CategoryResponse response = chatClient.prompt()
                .system(categorizePrompt)
                .options(ChatOptions.builder()
                        .model(chatModel)
                        .temperature(0.7)
                        .build())
                .user(userInput)
                .call()
                .entity(CategoryResponse.class);

        return response.getCategory();
    }

}
