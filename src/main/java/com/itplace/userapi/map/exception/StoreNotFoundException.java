package com.itplace.userapi.map.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class StoreNotFoundException extends BusinessException {
    private final BaseCode code;

    public StoreNotFoundException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
