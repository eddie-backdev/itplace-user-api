package com.itplace.userapi.recommend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OpenAiWebClientConfig {

    @Bean(name = "openAiWebClient")
    public WebClient openAiwebClient(WebClient.Builder builder) {
        return builder
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

}
