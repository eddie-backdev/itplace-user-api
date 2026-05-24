package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoUserProfile;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class KakaoOAuthProviderClientImpl implements KakaoOAuthProviderClient {

    private static final Duration KAKAO_API_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient.Builder webClientBuilder;

    @Value("${spring.security.oauth2.client.registration.kakao.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.kakao.client-secret:}")
    private String clientSecret;

    @Value("${spring.security.oauth2.client.registration.kakao.redirect-uri}")
    private String defaultRedirectUri;

    @Value("${spring.security.oauth2.client.provider.kakao.token-uri:https://kauth.kakao.com/oauth/token}")
    private String tokenUri;

    @Value("${spring.security.oauth2.client.provider.kakao.user-info-uri:https://kapi.kakao.com/v2/user/me}")
    private String userInfoUri;

    @Override
    public KakaoUserProfile fetchUser(String authorizationCode, String redirectUri) {
        String accessToken = requestAccessToken(authorizationCode, StringUtils.hasText(redirectUri) ? redirectUri : defaultRedirectUri);
        Map<String, Object> userInfo = requestUserInfo(accessToken);
        String providerId = String.valueOf(userInfo.get("id"));
        if (!StringUtils.hasText(providerId) || "null".equals(providerId)) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_INPUT_VALUE);
        }

        return new KakaoUserProfile(providerId, nestedString(userInfo, "kakao_account", "email"));
    }

    private String requestAccessToken(String authorizationCode, String redirectUri) {
        Map<String, Object> tokenPayload;
        try {
            BodyInserters.FormInserter<String> form = BodyInserters
                    .fromFormData("grant_type", "authorization_code")
                    .with("client_id", clientId)
                    .with("redirect_uri", redirectUri)
                    .with("code", authorizationCode);

            if (StringUtils.hasText(clientSecret)) {
                form.with("client_secret", clientSecret);
            }

            tokenPayload = webClientBuilder.build()
                    .post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(KAKAO_API_TIMEOUT);
        } catch (RuntimeException ex) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_INPUT_VALUE);
        }

        String accessToken = tokenPayload == null ? null : String.valueOf(tokenPayload.get("access_token"));
        if (!StringUtils.hasText(accessToken) || "null".equals(accessToken)) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_INPUT_VALUE);
        }
        return accessToken;
    }

    private Map<String, Object> requestUserInfo(String accessToken) {
        try {
            Map<String, Object> userInfo = webClientBuilder.build()
                    .get()
                    .uri(userInfoUri)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(KAKAO_API_TIMEOUT);
            if (userInfo == null) {
                throw new InvalidCredentialsException(SecurityCode.INVALID_INPUT_VALUE);
            }
            return userInfo;
        } catch (RuntimeException ex) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_INPUT_VALUE);
        }
    }

    @SuppressWarnings("unchecked")
    private String nestedString(Map<String, Object> root, String objectKey, String valueKey) {
        Object nested = root.get(objectKey);
        if (!(nested instanceof Map<?, ?> nestedMap)) {
            return null;
        }
        Object value = ((Map<String, Object>) nestedMap).get(valueKey);
        return value == null ? null : String.valueOf(value);
    }
}
