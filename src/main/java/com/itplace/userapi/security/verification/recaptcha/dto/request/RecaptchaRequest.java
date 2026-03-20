package com.itplace.userapi.security.verification.recaptcha.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecaptchaRequest {
    private String recaptchaToken;
}
