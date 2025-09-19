package com.itplace.userapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
@EnableJpaRepositories(  // jpa 사용되는 패키지
        basePackages = {
                "com.itplace.userapi.benefit",
                "com.itplace.userapi.common",
                "com.itplace.userapi.favorite",
                "com.itplace.userapi.history",
                "com.itplace.userapi.map",
                "com.itplace.userapi.partner",
                "com.itplace.userapi.security",
                "com.itplace.userapi.user",
                "com.itplace.userapi.recommend",
                "com.itplace.userapi.ai",
                "com.itplace.userapi.event"
        }
)
@EnableMongoRepositories(  // mongo 사용되는 패키지
        basePackages = "com.itplace.userapi.log"
)
@OpenAPIDefinition(servers = {@Server(url = "/", description = "Default Server URL")})
public class ItplaceUserApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItplaceUserApiApplication.class, args);
    }

}
