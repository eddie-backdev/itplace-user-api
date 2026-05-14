package com.itplace.userapi.ai.forbiddenword.service;

import com.itplace.userapi.ai.forbiddenword.entity.ExceptionWord;
import com.itplace.userapi.ai.forbiddenword.entity.ForbiddenWord;
import com.itplace.userapi.ai.forbiddenword.repository.ExceptionWordRepository;
import com.itplace.userapi.ai.forbiddenword.repository.ForbiddenWordRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayDeque;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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

    // 노드 정의
    private static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        TrieNode fail = null;       // 실패 링크
        boolean isEnd = false;      // 금칙어 끝 표시
    }

    private final TrieNode root = new TrieNode();

    private final Set<String> specialForbiddenWords = new HashSet<>();  // 특수문자 금칙어 저장
    private final Set<String> normalizedForbiddenWords = new HashSet<>();

    private Set<String> exceptionWords = new HashSet<>();

    @PostConstruct
    public void init() {
        List<String> forbiddenWords = forbiddenWordRepository.findAll()
                .stream()
                .map(ForbiddenWord::getWord)
                .toList();

        List<ExceptionWord> exceptionWordList = exceptionWordRepository.findAll();
        exceptionWords = exceptionWordList.stream()
                .map(ExceptionWord::getWord)
                .map(this::normalize)
                .filter(word -> !word.isBlank())
                .collect(Collectors.toSet());

        log.debug("=== 금칙어 init() ===");
        for (String w : forbiddenWords) {
            insert(w);
        }
        buildFailureLinks();
    }

    @Scheduled(fixedDelay = 600_000)
    @Override
    public void reloadForbiddenWords() {
        synchronized (root) {
            root.children.clear();
            root.fail = root;
            root.isEnd = false;
            specialForbiddenWords.clear();
            normalizedForbiddenWords.clear();

            List<String> forbiddenWords = forbiddenWordRepository.findAll()
                    .stream()
                    .map(ForbiddenWord::getWord)
                    .toList();

            for (String w : forbiddenWords) {
                insert(w);
            }
            buildFailureLinks();

            List<ExceptionWord> exceptionWordList = exceptionWordRepository.findAll();
            exceptionWords = exceptionWordList.stream()
                    .map(word -> normalize(word.getWord()))
                    .collect(Collectors.toSet());

            log.info("금칙어 및 예외 단어 재로딩 완료");
        }
    }

    private void insert(String word) {
        if (isSpecialWord(word)) {
            specialForbiddenWords.add(word);  // 특수문자 금칙어는 Set에 저장
        }

        String normalized = normalize(word);

        if (normalized.isEmpty()) {
            return;  // 정규화 후 빈 문자열 무시
        }
        normalizedForbiddenWords.add(normalized);

        TrieNode node = root;
        for (char c : normalized.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.isEnd = true;
    }

    private void buildFailureLinks() {
        Queue<TrieNode> q = new ArrayDeque<>();
        root.fail = root;

        for (TrieNode child : root.children.values()) {
            child.fail = root;
            q.add(child);
        }

        while (!q.isEmpty()) {
            TrieNode curr = q.poll();
            for (Map.Entry<Character, TrieNode> e : curr.children.entrySet()) {
                char c = e.getKey();
                TrieNode next = e.getValue();

                TrieNode f = curr.fail;
                while (f != root && !f.children.containsKey(c)) {
                    f = f.fail;
                }
                next.fail = f.children.getOrDefault(c, root);
                next.isEnd |= next.fail.isEnd;
                q.add(next);
            }
        }
    }

    @Override
    public boolean containsForbiddenWord(String text) {
        String normalized = normalize(text);

        // 기존 금칙어 검사
        String matchedWord = findForbiddenWord(text, normalized);
        if (matchedWord != null) {
            // 예외 단어 포함 시 욕설 아님 처리
            for (String exception : exceptionWords) {
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
        return text.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "")
                .toLowerCase()
                .replaceAll("\\s+", "");
    }

    private boolean isSpecialWord(String word) {
        return !word.replaceAll("[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "").isEmpty();
    }


    private boolean checkTrieForbiddenWord(String normalized) {
        TrieNode node = root;

        for (char c : normalized.toCharArray()) {
            while (node != root && !node.children.containsKey(c)) {
                node = node.fail;
            }
            node = node.children.getOrDefault(c, root);
            if (node.isEnd) {
                return true;
            }
        }
        return false;
    }

    private String findForbiddenWord(String original, String normalized) {
        Set<String> tokens = normalizedTokens(original);
        for (String forbidden : normalizedForbiddenWords) {
            if (matchesForbiddenWord(normalized, tokens, forbidden)) {
                return forbidden;
            }
        }
        for (String forbidden : specialForbiddenWords) {
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

    private boolean checkSpecialForbiddenWord(String normalized) {
        for (String forbidden : specialForbiddenWords) {
            if (normalized.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String censor(String text) {
        return containsForbiddenWord(text)
                ? "입력할 수 없는 단어가 포함되어 있습니다."
                : "호출되었습니다.";
    }
}
