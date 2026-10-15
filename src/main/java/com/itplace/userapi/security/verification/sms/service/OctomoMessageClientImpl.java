package com.itplace.userapi.security.verification.sms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.support.SensitiveDataMasker;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
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
                    "Octomo 문자 인증 조회 결과: mobileNumber={}, textLength={}, status={}, verified={}",
                    SensitiveDataMasker.maskPhoneNumber(mobileNumber),
                    text == null ? 0 : text.length(),
                    response.getStatusCode(),
                    verified
            );
            return verified;
        } catch (RestClientException e) {
            log.warn(
                    "Octomo 문자 인증 조회 실패: mobileNumber={}, errorType={}",
                    SensitiveDataMasker.maskPhoneNumber(mobileNumber),
                    e.getClass().getSimpleName()
            );
            throw new SmsVerificationException(SecurityCode.SMS_PROVIDER_UNAVAILABLE);
        }
    }

    private boolean parseVerified(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new SmsVerificationException(SecurityCode.SMS_PROVIDER_UNAVAILABLE);
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            boolean recognized = false;
            boolean verified = false;
            for (JsonNode node : List.of(root, root.path("data"))) {
                for (String field : List.of("verified", "exists")) {
                    JsonNode value = node.path(field);
                    if (value.isBoolean() || (value.isTextual()
                            && ("true".equals(value.asText()) || "false".equals(value.asText())))) {
                        recognized = true;
                        verified |= value.asBoolean();
                    }
                }
            }
            if (recognized) {
                return verified;
            }
        } catch (Exception e) {
            log.warn("Octomo 응답을 해석할 수 없습니다: {}", e.getClass().getSimpleName());
        }
        throw new SmsVerificationException(SecurityCode.SMS_PROVIDER_UNAVAILABLE);
    }
}
