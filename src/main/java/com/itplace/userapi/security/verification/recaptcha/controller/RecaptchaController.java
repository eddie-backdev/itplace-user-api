package com.itplace.userapi.security.verification.recaptcha.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.verification.recaptcha.dto.RecaptchaRequest;
import com.itplace.userapi.security.verification.recaptcha.service.RecaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RecaptchaController {

    private final RecaptchaService recaptchaService;

    @PostMapping("/recaptcha")
    public ResponseEntity<ApiResponse<Void>> recaptcha(@RequestBody @Validated RecaptchaRequest request) {
        boolean isRecaptchaVerified = recaptchaService.verifyRecaptcha(request);
        if (isRecaptchaVerified) {
            ApiResponse<Void> body = ApiResponse.of(SecurityCode.RECAPTCHA_SUCCESS, null);
            return new ResponseEntity<>(body, body.getStatus());
        } else {
            ApiResponse<Void> body = ApiResponse.of(SecurityCode.INVALID_INPUT_VALUE, null);
            return new ResponseEntity<>(body, body.getStatus());
        }
    }
}
