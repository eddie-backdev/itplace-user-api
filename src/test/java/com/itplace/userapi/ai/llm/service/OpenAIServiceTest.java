package com.itplace.userapi.ai.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAIServiceTest {

    @Test
    void startsWithoutLegacyPromptPropertiesOrFiles() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenAIService.class)
                .withBean(OpenAiChatModel.class, () -> mock(OpenAiChatModel.class))
                .withPropertyValues("spring.ai.openai.chat.model=gpt-5-nano")
                .run(context -> assertThat(context).hasSingleBean(OpenAIService.class));
    }

    @Test
    void chatOptionsUseGpt5CompatibleDefaultTemperature() {
        OpenAIService openAIService = new OpenAIService(null);
        ReflectionTestUtils.setField(openAIService, "chatModel", "gpt-5-nano");

        OpenAiChatOptions options = openAIService.chatOptions(700);

        assertThat(options.getModel()).isEqualTo("gpt-5-nano");
        assertThat(options.getTemperature()).isEqualTo(1.0d);
        assertThat(options.getMaxCompletionTokens()).isEqualTo(700);
        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void chatOptionsSkipReasoningEffortForNonGpt5Model() {
        OpenAIService openAIService = new OpenAIService(null);
        ReflectionTestUtils.setField(openAIService, "chatModel", "gpt-4o-mini");

        OpenAiChatOptions options = openAIService.chatOptions(700);

        assertThat(options.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(options.getTemperature()).isEqualTo(1.0d);
        assertThat(options.getMaxCompletionTokens()).isEqualTo(700);
        assertThat(options.getReasoningEffort()).isNull();
    }
}
