package com.itplace.userapi.security.verification.sms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itplace.userapi.user.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SmsConfirmResponse {

    private UserStatus userStatus;
    @JsonProperty("isLocalUser")
    private boolean localUser;
    private boolean uplusDataExists;
}