package com.itplace.userapi.common.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("itPlace API Document")
                        .description("지도 기반 내 주변 LG유플러스 멤버십 혜택 정보 조회 서비스 'itPlace'의 API 명세서입니다.\n\n모든 API 응답은 표준 응답 포맷(ApiResponse)을 따릅니다.")
                        .version("1.0.0"))
                .addServersItem(new Server().url("http://localhost:8080").description("로컬 테스트 서버"))
                .addServersItem(new Server().url("https://userapi.itplace.click").description("개발 서버"))
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("accessToken")
                                .description("로그인 후 발급되는 JWT 액세스 토큰 쿠키")));
    }
}
