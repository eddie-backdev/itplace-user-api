package com.itplace.userapi.common.redis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RedisKeyPrefix {
    OTP_SMS("sms:"),
    OTP_EMAIL("email:"),
    REFRESH_TOKEN("refresh:"),
    OAUTH_TEMP_USER("oauth:temp:");

    private final String prefix;
}
