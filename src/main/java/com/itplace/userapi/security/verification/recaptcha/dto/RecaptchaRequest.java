package com.itplace.userapi.security.verification.recaptcha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecaptchaRequest {
    private String recaptchaToken;
}
