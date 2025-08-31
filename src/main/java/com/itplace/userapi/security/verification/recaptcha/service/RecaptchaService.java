package com.itplace.userapi.security.verification.recaptcha.service;

import com.itplace.userapi.security.verification.recaptcha.dto.RecaptchaRequest;
import com.itplace.userapi.security.verification.recaptcha.dto.RecaptchaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class RecaptchaService {

    private final WebClient webClient;

    @Value("${google.recaptcha.key.url}")
    private String recaptchaUrl;

    @Value("${google.recaptcha.key.secret}")
    private String recaptchaSecret;

    public boolean verifyRecaptcha(RecaptchaRequest request) {
        String recaptchaToken = request.getRecaptchaToken();
        if (recaptchaToken == null || recaptchaToken.isEmpty()) {
            return false;
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("secret", recaptchaSecret);
        formData.add("response", recaptchaToken);

        RecaptchaResponse response = webClient.post()
                .uri(recaptchaUrl)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(RecaptchaResponse.class)
                .block();

        return response != null && response.isSuccess();
    }
}
