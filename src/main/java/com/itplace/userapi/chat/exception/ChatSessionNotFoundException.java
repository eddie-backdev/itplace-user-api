package com.itplace.userapi.chat.exception;

import com.itplace.userapi.chat.ChatCode;
import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;

public class ChatSessionNotFoundException extends BusinessException {

    public ChatSessionNotFoundException() {
        super(ChatCode.SESSION_NOT_FOUND.getMessage());
    }

    @Override
    public BaseCode getCode() {
        return ChatCode.SESSION_NOT_FOUND;
    }
}
