package com.itplace.userapi.common;

import com.itplace.userapi.log.interceptor.LoggingInterceptor;
import com.itplace.userapi.common.exception.AiDisabledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoggingInterceptor loggingInterceptor;

    @Value("${app.ai.enabled:false}")
    private boolean aiEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!aiEnabled) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    throw new AiDisabledException();
                }
            }).addPathPatterns("/api/v1/questions/**", "/api/v1/recommendations", "/api/v1/recommendations/**",
                    "/api/v1/maps/nearby/itplace-ai")
                    .excludePathPatterns("/api/v1/questions/chat", "/api/v1/questions/chat/stream");
        }
        registry.addInterceptor(loggingInterceptor)
                .addPathPatterns("/api/v1/benefits/{benefitId}", "/api/v1/benefit/{benefitId}");
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
