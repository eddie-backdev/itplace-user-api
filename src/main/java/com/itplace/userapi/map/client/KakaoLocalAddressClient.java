package com.itplace.userapi.map.client;

import com.itplace.userapi.map.dto.kakao.KakaoCoordinateAddressResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class KakaoLocalAddressClient {

    private final RestClient restClient;
    private final String restApiKey;

    public KakaoLocalAddressClient(
            RestClient.Builder restClientBuilder,
            @Value("${kakao.local.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${kakao.local.rest-api-key:${KAKAO_REST_API_KEY:}}") String restApiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.restApiKey = restApiKey;
    }

    public Optional<String> findRegionAddress(double lat, double lng) {
        if (!StringUtils.hasText(restApiKey)) {
            log.warn("Kakao Local REST API key is not configured");
            return Optional.empty();
        }

        try {
            KakaoCoordinateAddressResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/local/geo/coord2address.json")
                            .queryParam("x", lng)
                            .queryParam("y", lat)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(KakaoCoordinateAddressResponse.class);

            if (response == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.toRegionAddress());
        } catch (RestClientException e) {
            log.warn("Kakao Local reverse geocoding failed: lat={}, lng={}, reason={}", lat, lng, e.getMessage());
            return Optional.empty();
        }
    }
}
