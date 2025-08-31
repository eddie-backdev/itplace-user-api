package com.itplace.userapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication(exclude = {PgVectorStoreAutoConfiguration.class})
@OpenAPIDefinition(servers = {@Server(url = "/", description = "Default Server URL")})
public class ItplaceUserApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItplaceUserApiApplication.class, args);
    }

}
