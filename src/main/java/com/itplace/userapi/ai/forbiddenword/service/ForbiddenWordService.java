package com.itplace.userapi.ai.forbiddenword.service;

public interface ForbiddenWordService {
    boolean containsForbiddenWord(String text);

    String censor(String text);

    void reloadForbiddenWords();
}
