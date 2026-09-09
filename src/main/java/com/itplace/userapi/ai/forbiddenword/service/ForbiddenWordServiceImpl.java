package com.itplace.userapi.ai.forbiddenword.service;

import com.itplace.userapi.ai.forbiddenword.entity.ExceptionWord;
import com.itplace.userapi.ai.forbiddenword.entity.ForbiddenWord;
import com.itplace.userapi.ai.forbiddenword.repository.ExceptionWordRepository;
import com.itplace.userapi.ai.forbiddenword.repository.ForbiddenWordRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ForbiddenWordServiceImpl implements ForbiddenWordService {
    private static final int MIN_SUBSTRING_MATCH_LENGTH = 3;

    private final ForbiddenWordRepository forbiddenWordRepository;
    private final ExceptionWordRepository exceptionWordRepository;

    private record Rules(Set<String> normalized, Set<String> special, Set<String> exceptions) {}

    private volatile Rules rules = new Rules(Set.of(), Set.of(), Set.of());

    @PostConstruct
    public void init() {
        reloadForbiddenWords();
    }

    @Scheduled(fixedDelay = 600_000)
    @Override
    public synchronized void reloadForbiddenWords() {
        List<String> words = forbiddenWordRepository.findAll().stream().map(ForbiddenWord::getWord).toList();
        Set<String> normalized = words.stream().map(this::normalize).filter(word -> !word.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> special = words.stream().filter(this::isSpecialWord).collect(Collectors.toUnmodifiableSet());
        Set<String> exceptions = exceptionWordRepository.findAll().stream().map(ExceptionWord::getWord)
                .map(this::normalize).filter(word -> !word.isBlank()).collect(Collectors.toUnmodifiableSet());
        // 두 조회가 모두 성공한 뒤 교체하므로 실패 중에도 마지막 정상 규칙으로 검사한다.
        rules = new Rules(normalized, special, exceptions);
        log.info("금칙어 및 예외 단어 재로딩 완료");
    }

    @Override
    public boolean containsForbiddenWord(String text) {
        Rules snapshot = rules;
        String normalized = normalize(text);

        // 기존 금칙어 검사
        String matchedWord = findForbiddenWord(text, normalized, snapshot);
        if (matchedWord != null) {
            // 예외 단어 포함 시 욕설 아님 처리
            for (String exception : snapshot.exceptions()) {
                if (normalized.contains(exception)) {
                    return false;
                }
            }
            // 예외 단어가 없으면 금칙어 포함으로 처리
            log.debug("금칙어 감지: matchedWord={}, normalizedLength={}", matchedWord, normalized.length());
            return true;
        }
        return false;
    }

    private String normalize(String text) {
        return (text == null ? "" : text).replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private boolean isSpecialWord(String word) {
        return !word.replaceAll("[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "").isEmpty();
    }


    private String findForbiddenWord(String original, String normalized, Rules snapshot) {
        Set<String> tokens = normalizedTokens(original);
        for (String forbidden : snapshot.normalized()) {
            if (matchesForbiddenWord(normalized, tokens, forbidden)) {
                return forbidden;
            }
        }
        for (String forbidden : snapshot.special()) {
            if (original != null && original.contains(forbidden)) {
                return forbidden;
            }
        }
        return null;
    }

    private boolean matchesForbiddenWord(String normalized, Set<String> tokens, String forbidden) {
        if (forbidden == null || forbidden.isBlank()) {
            return false;
        }
        if (tokens.contains(forbidden) || normalized.equals(forbidden)) {
            return true;
        }
        if (forbidden.length() < MIN_SUBSTRING_MATCH_LENGTH) {
            return false;
        }
        return normalized.contains(forbidden);
    }

    private Set<String> normalizedTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.split("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+"))
                .map(this::normalize)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public String censor(String text) {
        return containsForbiddenWord(text)
                ? "입력할 수 없는 단어가 포함되어 있습니다."
                : "호출되었습니다.";
    }
}
