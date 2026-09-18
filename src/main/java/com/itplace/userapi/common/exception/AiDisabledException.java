package com.itplace.userapi.common.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.CommonCode;

public class AiDisabledException extends BusinessException {
    public AiDisabledException() {
        super(CommonCode.AI_DISABLED.getMessage());
    }

    @Override
    public BaseCode getCode() {
        return CommonCode.AI_DISABLED;
    }
}
