package com.itplace.userapi.ai.question.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;

public class QuestionException extends BusinessException {

    private final BaseCode code;

    public QuestionException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }

    @Override
    public BaseCode getCode() {
        return this.code;
    }
}

