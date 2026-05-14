package com.itplace.userapi.ai.forbiddenword.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.forbiddenword.entity.ExceptionWord;
import com.itplace.userapi.ai.forbiddenword.entity.ForbiddenWord;
import com.itplace.userapi.ai.forbiddenword.repository.ExceptionWordRepository;
import com.itplace.userapi.ai.forbiddenword.repository.ForbiddenWordRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForbiddenWordServiceImplTest {

    @Mock
    private ForbiddenWordRepository forbiddenWordRepository;

    @Mock
    private ExceptionWordRepository exceptionWordRepository;

    @Test
    void containsForbiddenWord_doesNotBlockNormalRecommendationQuestionByShortSubstring() {
        when(forbiddenWordRepository.findAll()).thenReturn(List.of(
                forbiddenWord("되는"),
                forbiddenWord("할인")
        ));
        when(exceptionWordRepository.findAll()).thenReturn(List.of());
        ForbiddenWordServiceImpl service = service();

        assertThat(service.containsForbiddenWord("근처에서 할인되는 카페 알려줘")).isFalse();
        assertThat(service.censor("근처에서 할인되는 카페 알려줘")).isEqualTo("호출되었습니다.");
    }

    @Test
    void containsForbiddenWord_stillBlocksExactShortForbiddenToken() {
        when(forbiddenWordRepository.findAll()).thenReturn(List.of(forbiddenWord("욕")));
        when(exceptionWordRepository.findAll()).thenReturn(List.of());
        ForbiddenWordServiceImpl service = service();

        assertThat(service.containsForbiddenWord("욕")).isTrue();
    }

    @Test
    void containsForbiddenWord_blocksLongForbiddenSubstring() {
        when(forbiddenWordRepository.findAll()).thenReturn(List.of(forbiddenWord("나쁜말")));
        when(exceptionWordRepository.findAll()).thenReturn(List.of());
        ForbiddenWordServiceImpl service = service();

        assertThat(service.containsForbiddenWord("이건나쁜말입니다")).isTrue();
    }

    @Test
    void containsForbiddenWord_appliesNormalizedExceptionWordsOnInitialLoad() {
        when(forbiddenWordRepository.findAll()).thenReturn(List.of(forbiddenWord("나쁜말")));
        when(exceptionWordRepository.findAll()).thenReturn(List.of(exceptionWord("나쁜말아님")));
        ForbiddenWordServiceImpl service = service();

        assertThat(service.containsForbiddenWord("나쁜말아님")).isFalse();
    }

    private ForbiddenWordServiceImpl service() {
        ForbiddenWordServiceImpl service = new ForbiddenWordServiceImpl(forbiddenWordRepository, exceptionWordRepository);
        service.init();
        return service;
    }

    private ForbiddenWord forbiddenWord(String word) {
        return ForbiddenWord.builder()
                .word(word)
                .wordClass("test")
                .build();
    }

    private ExceptionWord exceptionWord(String word) {
        return ExceptionWord.builder()
                .word(word)
                .build();
    }
}
