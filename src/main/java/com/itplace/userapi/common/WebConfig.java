package com.itplace.userapi.common;

import com.itplace.userapi.log.interceptor.LoggingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoggingInterceptor loggingInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 엔드포인트에 대해
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:5173",
                        "http://localhost:5174",
                        "http://localhost:8080",
                        "https://itplace.click",
                        "https://www.itplace.click",
                        "https://userapi.itplace.click") // 허용할 Origin
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 허용할 HTTP 메서드
                .allowedHeaders("*") // 모든 헤더 허용
                .allowCredentials(true) // 쿠키 허용 여부
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loggingInterceptor)
                .addPathPatterns("/api/v1/benefit/{benefitId}");
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
