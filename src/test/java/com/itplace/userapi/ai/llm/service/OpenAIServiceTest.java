package com.itplace.userapi.ai.llm.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAIServiceTest {

    @Test
    void chatOptionsUseGpt5CompatibleDefaultTemperature() {
        OpenAIService openAIService = new OpenAIService(null);
        ReflectionTestUtils.setField(openAIService, "chatModel", "gpt-5-nano");

        OpenAiChatOptions options = openAIService.chatOptions();

        assertThat(options.getModel()).isEqualTo("gpt-5-nano");
        assertThat(options.getTemperature()).isEqualTo(1.0d);
    }
}
