package com.itplace.userapi.ai.forbiddenword.exception;

import com.itplace.userapi.ai.forbiddenword.ForbiddenWordCode;
import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;

public class ForbiddenWordException extends BusinessException {

    public ForbiddenWordException() {
        super("질문에 금칙어가 포함되어 있습니다.");
    }

    public BaseCode getCode() {
        return ForbiddenWordCode.FORBIDDEN_WORD_DETECTED;
    }
}

