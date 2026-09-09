package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAIServiceImplTest {

    @Test
    void emptyCandidatesSkipExternalCalls() {
        var client = org.mockito.Mockito.mock(org.springframework.web.reactive.function.client.WebClient.class);
        var service = new OpenAIServiceImpl(new com.fasterxml.jackson.databind.ObjectMapper(), client, null, null, null);
        assertThat(service.rerankAndExplain(null, java.util.List.of(), 5)).isEmpty();
        assertThat(service.rerankAndExplain(null, null, 5)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void truncateForPrompt_limitsLongTextAndNormalizesLineBreaks() {
        String text = "12345\n67890\r\nABCDE";

        String result = OpenAIServiceImpl.truncateForPrompt(text, 8, "기본값");

        assertThat(result).isEqualTo("12345 67…");
    }

    @Test
    void truncateForPrompt_usesDefaultForBlankText() {
        String result = OpenAIServiceImpl.truncateForPrompt("   ", 10, "기본값");

        assertThat(result).isEqualTo("기본값");
    }
}
