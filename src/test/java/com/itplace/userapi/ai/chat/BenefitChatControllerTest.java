package com.itplace.userapi.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class BenefitChatControllerTest {
    private final BenefitChatService service = mock(BenefitChatService.class);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new BenefitChatController(service, executor))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void streamSendsProgressCardsThenFinalDone() throws Exception {
        BenefitChatResponse cards = new BenefitChatResponse("", java.util.List.of(), "ANSWER", java.util.List.of(), "trace");
        BenefitChatResponse result = new BenefitChatResponse("완료", java.util.List.of(), "ANSWER", java.util.List.of(), "trace");
        when(service.chat(any(BenefitChatRequest.class), isNull(), any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(2, java.util.function.BiConsumer.class);
            callback.accept("status", java.util.Map.of("stage", "planning"));
            callback.accept("status", java.util.Map.of("stage", "searching"));
            callback.accept("cards", cards);
            callback.accept("status", java.util.Map.of("stage", "answering"));
            return result;
        });

        MvcResult started = mvc.perform(post("/api/v1/questions/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"카페 혜택\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult completed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk()).andReturn();

        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:status", "event:cards", "event:done");
        assertThat(body).contains("data:{\"stage\":\"planning\"}", "data:{\"stage\":\"searching\"}",
                "data:{\"stage\":\"answering\"}", "\"status\":\"ANSWER\"", "\"reason\":\"\"", "\"reason\":\"완료\"");
        assertThat(body.indexOf("planning")).isLessThan(body.indexOf("searching"));
        assertThat(body.indexOf("searching")).isLessThan(body.indexOf("event:cards"));
        assertThat(body.indexOf("event:cards")).isLessThan(body.indexOf("answering"));
        assertThat(body.indexOf("answering")).isLessThan(body.indexOf("event:done"));
        verify(service).chat(any(BenefitChatRequest.class), isNull(), any());
    }

    @Test
    void invalidRequestDoesNotInvokeService() throws Exception {
        mvc.perform(post("/api/v1/questions/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void serviceFailureSendsSafeErrorEvent() throws Exception {
        when(service.chat(any(BenefitChatRequest.class), isNull(), any()))
                .thenThrow(new IllegalStateException("secret detail"));
        MvcResult started = mvc.perform(post("/api/v1/questions/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{\"question\":\"카페 혜택\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:error", "\"retryable\":true", "요청을 처리하지 못했습니다");
        assertThat(body).doesNotContain("secret detail", "event:done");
    }

    @Test
    void saturatedExecutorReturnsRetryableErrorWithoutStartingService() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        executor.execute(new FutureTask<>(() -> null));
        try {
            MvcResult started = mvc.perform(post("/api/v1/questions/chat/stream")
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                            .content("{\"question\":\"카페 혜택\"}"))
                    .andExpect(request().asyncStarted()).andReturn();
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("event:error", "\"retryable\":true", "요청이 많습니다");
            org.mockito.Mockito.verifyNoInteractions(service);
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void completionCancelsWorkerAndPreventsFurtherEvents() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch callbackAborted = new CountDownLatch(1);
        when(service.chat(any(BenefitChatRequest.class), isNull(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var callback = (java.util.function.BiConsumer<String, Object>) invocation.getArgument(2);
            workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            try {
                callback.accept("status", java.util.Map.of("stage", "answering"));
            } catch (java.util.concurrent.CancellationException cancelled) {
                callbackAborted.countDown();
                throw cancelled;
            }
            return new BenefitChatResponse("should not finish", java.util.List.of(), "ANSWER", java.util.List.of(), "trace");
        });

        MvcResult started = mvc.perform(post("/api/v1/questions/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"카페 혜택\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        started.getRequest().getAsyncContext().complete();

        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackAborted.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
