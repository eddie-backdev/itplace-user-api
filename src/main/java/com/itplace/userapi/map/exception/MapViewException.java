package com.itplace.userapi.map.exception;

import com.itplace.userapi.common.exception.BusinessException;
import com.itplace.userapi.map.StoreCode;

public class MapViewException extends BusinessException {
    private final StoreCode code;

    public MapViewException(StoreCode code) {
        super(code.getMessage());
        this.code = code;
    }

    @Override
    public StoreCode getCode() {
        return code;
    }
}
