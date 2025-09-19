package com.itplace.userapi.partner.exception;

import com.itplace.userapi.common.BaseCode;
import com.itplace.userapi.common.exception.BusinessException;
import lombok.Getter;

@Getter
public class PartnerNotFoundException extends BusinessException {
    private final BaseCode code;

    public PartnerNotFoundException(BaseCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
