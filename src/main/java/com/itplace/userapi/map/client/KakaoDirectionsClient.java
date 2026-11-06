package com.itplace.userapi.map.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.common.exception.BusinessException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class KakaoDirectionsClient {

    private final RestClient mapClient;
    private final RestClient carClient;
    private final String restApiKey;

    public KakaoDirectionsClient(
            RestClient.Builder builder,
            @Value("${kakao.directions.map-base-url:https://dapi.kakao.com}") String mapBaseUrl,
            @Value("${kakao.directions.car-base-url:https://apis-navi.kakaomobility.com}") String carBaseUrl,
            @Value("${kakao.local.rest-api-key:${KAKAO_REST_API_KEY:}}") String restApiKey,
            @Value("${kakao.directions.connect-timeout:2s}") Duration connectTimeout,
            @Value("${kakao.directions.read-timeout:6s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.mapClient = builder.clone().baseUrl(mapBaseUrl).requestFactory(requestFactory).build();
        this.carClient = builder.clone().baseUrl(carBaseUrl).requestFactory(requestFactory).build();
        this.restApiKey = restApiKey;
    }

    public JsonNode walk(double originLat, double originLng, double destinationLat, double destinationLng) {
        return get(mapClient, uri -> uri
                .path("/v2/routing/walk")
                .queryParam("start_x", originLng)
                .queryParam("start_y", originLat)
                .queryParam("end_x", destinationLng)
                .queryParam("end_y", destinationLat));
    }

    public JsonNode transit(double originLat, double originLng, double destinationLat, double destinationLng) {
        return get(mapClient, uri -> uri
                .path("/v2/routing/publictraffic")
                .queryParam("start_x", originLng)
                .queryParam("start_y", originLat)
                .queryParam("end_x", destinationLng)
                .queryParam("end_y", destinationLat));
    }

    public JsonNode car(double originLat, double originLng, double destinationLat, double destinationLng) {
        return get(carClient, uri -> uri
                .path("/v1/directions")
                .queryParam("origin", originLng + "," + originLat)
                .queryParam("destination", destinationLng + "," + destinationLat)
                .queryParam("summary", false)
                .queryParam("alternatives", true));
    }

    private JsonNode get(RestClient client, java.util.function.UnaryOperator<org.springframework.web.util.UriBuilder> uri) {
        if (!StringUtils.hasText(restApiKey)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE);
        }
        try {
            return client.get()
                    .uri(builder -> uri.apply(builder).build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_UNAVAILABLE);
            }
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        } catch (ResourceAccessException e) {
            if (hasTimeoutCause(e)) {
                throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_TIMEOUT);
            }
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        } catch (RestClientException e) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
    }

    private boolean hasTimeoutCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            if (current instanceof ConnectException) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    public static class DirectionsProviderException extends BusinessException {
        private final StoreCode code;

        public DirectionsProviderException(StoreCode code) {
            super(code.getMessage());
            this.code = code;
        }

        @Override
        public StoreCode getCode() {
            return code;
        }
    }
}
