package com.itplace.userapi.security.verification.sms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.SmsVerificationException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class OctomoMessageClientImpl implements OctomoMessageClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OctomoMessageClientImpl(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${octomo.api.base-url:https://api.octoverse.kr/octomo/v1}") String baseUrl,
            @Value("${octomo.api.key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @Override
    public boolean exists(String mobileNumber, String text) {
        if (!StringUtils.hasText(apiKey)) {
            throw new SmsVerificationException(SecurityCode.SMS_PROVIDER_NOT_CONFIGURED);
        }

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/public/message/exists")
                    .header("Authorization", "Octomo " + apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .body(Map.of("mobileNum", mobileNumber, "text", text))
                    .retrieve()
                    .toEntity(String.class);

            boolean verified = parseVerified(response.getBody());
            log.info(
                    "Octomo 문자 인증 조회 결과: mobileNumber={}, text='{}', textLength={}, codePoints={}, status={}, verified={}, body={}",
                    mobileNumber,
                    text,
                    text == null ? 0 : text.length(),
                    toCodePoints(text),
                    response.getStatusCode(),
                    verified,
                    response.getBody()
            );
            return verified;
        } catch (RestClientException e) {
            log.warn("Octomo 문자 인증 조회 실패: mobileNumber={}, reason={}", mobileNumber, e.getMessage());
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }
    }

    private boolean parseVerified(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return isTrue(root, "verified")
                    || isTrue(root, "exists")
                    || isTrue(root.path("data"), "verified")
                    || isTrue(root.path("data"), "exists");
        } catch (Exception e) {
            log.warn("Octomo 응답 파싱 실패: {}", e.getMessage());
            return false;
        }
    }

    private boolean isTrue(JsonNode node, String fieldName) {
        return node != null && node.has(fieldName) && node.get(fieldName).asBoolean(false);
    }

    private String toCodePoints(String value) {
        return value == null ? "" : value.chars()
                .mapToObj(character -> String.format("U+%04X", character))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }
}
