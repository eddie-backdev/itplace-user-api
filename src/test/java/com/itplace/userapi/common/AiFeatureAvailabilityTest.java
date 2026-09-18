package com.itplace.userapi.common;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.log.interceptor.LoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AiFeatureAvailabilityTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfiguration.class, WebConfig.class, ProbeController.class,
                    GlobalExceptionHandler.class)
            .withBean(LoggingInterceptor.class, () -> mock(LoggingInterceptor.class));

    @Test
    void defaultDisabledAiRejectsAiRoutesAndKeepsOrdinaryRoutes() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
            for (String path : new String[]{"/api/v1/questions/recommend", "/api/v1/questions/search",
                    "/api/v1/recommendations", "/api/v1/maps/nearby/itplace-ai"}) {
                mvc.perform(get(path))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("AI_DISABLED"));
            }
            mvc.perform(post("/api/v1/questions/save"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AI_DISABLED"));
            mvc.perform(get("/api/v1/benefits")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/maps/nearby")).andExpect(status().isOk());
        });
    }

    @Test
    void explicitEnableAllowsAiRoutesAgain() {
        contextRunner.withPropertyValues("app.ai.enabled=true").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/v1/questions/recommend")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/recommendations")).andExpect(status().isOk());
        });
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {}

    @RestController
    static class ProbeController {
        @RequestMapping("/api/v1/**")
        String handle() {
            return "handled";
        }
    }
}
