package com.itplace.userapi.chat;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatCode implements BaseCode {
    SESSION_CREATED("CHAT_001", HttpStatus.CREATED, "채팅 세션이 생성되었습니다."),
    MESSAGES_FETCHED("CHAT_002", HttpStatus.OK, "메시지 목록을 조회했습니다."),
    SESSION_NOT_FOUND("CHAT_003", HttpStatus.NOT_FOUND, "채팅 세션을 찾을 수 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
