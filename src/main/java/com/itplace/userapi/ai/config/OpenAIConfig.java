package com.itplace.userapi.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAIConfig {

    @Bean
    ChatOptions defaultChatOptions(@Value("${spring.ai.openai.chat.model}") String model) {
        return ChatOptions.builder()
                .model(model)
                .temperature(0.7)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel, ChatOptions defaultChatOptions) {
        return ChatClient.builder(chatModel)
                .defaultOptions(defaultChatOptions)
                .build();
    }
}
