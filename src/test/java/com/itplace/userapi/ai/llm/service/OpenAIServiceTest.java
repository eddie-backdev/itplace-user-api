package com.itplace.userapi.ai.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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

    @Test
    void emptyOrUnselectableCandidatesSkipTheProvider() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        OpenAIService service = new OpenAIService(model);

        assertThat(service.selectBenefitIds("question", null, null, 2)).isEmpty();
        assertThat(service.selectBenefitIds("question", null, List.of(), 2)).isEmpty();
        assertThat(service.selectBenefitIds("question", null,
                List.of(Candidate.builder().benefitId(null).build()), 2)).isEmpty();
        assertThat(service.selectBenefitIds("question", null,
                List.of(Candidate.builder().benefitId(1L).build()), 0)).isEmpty();

        verifyNoInteractions(model);
    }

    @Test
    void providerIdsAreRestrictedDeduplicatedAndLimitedToCandidates() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"benefitIds\":[22,999,22,21,23]}")))));
        OpenAIService service = new OpenAIService(model);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "chatModel", "gpt-5-nano");
        List<Candidate> candidates = List.of(
                Candidate.builder().benefitId(21L).build(),
                Candidate.builder().benefitId(null).build(),
                Candidate.builder().benefitId(22L).build(),
                Candidate.builder().benefitId(23L).build());

        assertThat(service.selectBenefitIds("question", null, candidates, 3))
                .containsExactly(22L, 21L, 23L);
        assertThat(service.selectBenefitIds("question", null, candidates, 2))
                .containsExactly(22L, 21L);
    }

    @Test
    void nullOrEmptyProviderIdsDoNotInventBenefitIds() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("{\"benefitIds\":null}")))));
        OpenAIService service = new OpenAIService(model);
        ReflectionTestUtils.setField(service, "chatModel", "gpt-4o-mini");
        List<Candidate> candidates = List.of(Candidate.builder().benefitId(7L).build());

        assertThat(service.selectBenefitIds("question", null, candidates, 1)).isEmpty();
        assertThat(service.selectBenefitIds("question", null, candidates, 1)).isEmpty();
    }
}
