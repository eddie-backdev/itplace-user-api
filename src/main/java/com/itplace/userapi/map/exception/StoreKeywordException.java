package com.itplace.userapi.map.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class StoreKeywordException extends BusinessException {
    private final BaseCode code;

    public StoreKeywordException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
