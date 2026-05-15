package com.itplace.userapi.security.verification.sms.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OctomoMessageExistsResponse(Boolean verified, Boolean exists) {
    public boolean isVerified() {
        return Boolean.TRUE.equals(verified) || Boolean.TRUE.equals(exists);
    }
}
