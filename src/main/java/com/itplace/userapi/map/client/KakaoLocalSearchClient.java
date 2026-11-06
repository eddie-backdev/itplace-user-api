package com.itplace.userapi.map.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.common.exception.BusinessException;
import com.itplace.userapi.map.StoreCode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

@Component
public class KakaoLocalSearchClient {
    private final RestClient client;
    private final String restApiKey;

    public KakaoLocalSearchClient(
            RestClient.Builder builder,
            @Value("${kakao.local.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${kakao.local.rest-api-key:${KAKAO_REST_API_KEY:}}") String restApiKey,
            @Value("${kakao.local.connect-timeout:2s}") Duration connectTimeout,
            @Value("${kakao.local.read-timeout:3s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.restApiKey = restApiKey;
    }

    public JsonNode address(String query) {
        return get(uri -> uri.path("/v2/local/search/address.json").queryParam("query", query));
    }

    public JsonNode places(String query, Double centerLat, Double centerLng) {
        return get(uri -> {
            uri.path("/v2/local/search/keyword.json").queryParam("query", query);
            if (centerLat != null && centerLng != null) {
                uri.queryParam("x", centerLng).queryParam("y", centerLat).queryParam("sort", "distance");
            }
            return uri;
        });
    }

    private JsonNode get(UnaryOperator<UriBuilder> uri) {
        if (!StringUtils.hasText(restApiKey)) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
        }
        try {
            return client.get()
                    .uri(builder -> uri.apply(builder).build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 408) {
                throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT);
            }
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
            }
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        } catch (ResourceAccessException e) {
            if (hasTimeoutCause(e)) {
                throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT);
            }
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        } catch (RestClientException e) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        }
    }

    private boolean hasTimeoutCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            if (current instanceof ConnectException) {
                return false;
            }
        }
        return false;
    }

    public static class LocalSearchException extends BusinessException {
        private final StoreCode code;

        public LocalSearchException(StoreCode code) {
            super(code.getMessage());
            this.code = code;
        }

        @Override
        public StoreCode getCode() {
            return code;
        }
    }
}
