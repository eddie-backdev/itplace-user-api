package com.itplace.userapi.favorite.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class DuplicateFavoriteException extends BusinessException {
    private final BaseCode code;

    public DuplicateFavoriteException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}

