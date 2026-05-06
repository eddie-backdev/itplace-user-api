package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAIServiceImplTest {

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
