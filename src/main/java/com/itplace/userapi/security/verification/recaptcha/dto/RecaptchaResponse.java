package com.itplace.userapi.security.verification.recaptcha.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RecaptchaResponse {
    private boolean success;

    @JsonProperty("challenge_ts")
    private String challengeTs;

    private String hostname;
}