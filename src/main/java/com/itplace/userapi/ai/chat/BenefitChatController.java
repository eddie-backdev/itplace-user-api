package com.itplace.userapi.ai.chat;

import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatResponse;
import com.itplace.userapi.ai.question.QuestionCode;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/questions")
public class BenefitChatController {
    private final BenefitChatService benefitChatService;
    private final ThreadPoolTaskExecutor benefitChatStreamExecutor;

    public BenefitChatController(BenefitChatService benefitChatService,
            @Qualifier("benefitChatStreamExecutor") ThreadPoolTaskExecutor benefitChatStreamExecutor) {
        this.benefitChatService = benefitChatService;
        this.benefitChatStreamExecutor = benefitChatStreamExecutor;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<BenefitChatResponse>> chat(
            @Valid @RequestBody BenefitChatRequest request,
            @AuthenticationPrincipal PrincipalDetails principalDetails) {
        BenefitChatResponse result = benefitChatService.chat(request,
                principalDetails == null ? null : principalDetails.getUserId());
        return ApiResponse.of(QuestionCode.QUESTION_SUCCESS, result).toResponseEntity();
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(
            @Valid @RequestBody BenefitChatRequest request,
            @AuthenticationPrincipal PrincipalDetails principalDetails) {
        Long userId = principalDetails == null ? null : principalDetails.getUserId();
        SseEmitter emitter = new SseEmitter(45_000L);
        Object eventLock = new Object();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<FutureTask<Void>> workerRef = new AtomicReference<>();

        Runnable cancel = () -> {
            synchronized (eventLock) {
                terminal.set(true);
            }
            FutureTask<Void> worker = workerRef.get();
            if (worker != null) worker.cancel(true);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            synchronized (eventLock) {
                if (terminal.compareAndSet(false, true)) {
                    sendError(emitter, "요청 시간이 초과되었습니다. 다시 시도해 주세요.", true);
                }
            }
            FutureTask<Void> worker = workerRef.get();
            if (worker != null) worker.cancel(true);
        });
        emitter.onError(error -> cancel.run());

        FutureTask<Void> worker = new FutureTask<>(() -> {
            try {
                BenefitChatResponse result = benefitChatService.chat(request, userId, (event, data) -> {
                    synchronized (eventLock) {
                        if (terminal.get()) throw new java.util.concurrent.CancellationException();
                        send(emitter, event, data);
                    }
                });
                synchronized (eventLock) {
                    if (!terminal.get()) {
                        send(emitter, "done", result);
                        terminal.set(true);
                        emitter.complete();
                    }
                }
            } catch (java.util.concurrent.CancellationException ignored) {
                synchronized (eventLock) {
                    if (terminal.compareAndSet(false, true)) emitter.complete();
                }
            } catch (SseSendException sendFailure) {
                synchronized (eventLock) {
                    if (terminal.compareAndSet(false, true)) {
                        if (sendFailure.getCause() instanceof IOException) {
                            // The Servlet container owns completion after a client I/O failure.
                        } else {
                            sendError(emitter, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", true);
                        }
                    }
                }
            } catch (Exception exception) {
                synchronized (eventLock) {
                    if (terminal.compareAndSet(false, true)) {
                        sendError(emitter, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", true);
                    }
                }
            }
            return null;
        });
        workerRef.set(worker);
        try {
            benefitChatStreamExecutor.execute(worker);
        } catch (RejectedExecutionException rejected) {
            synchronized (eventLock) {
                if (terminal.compareAndSet(false, true)) {
                    sendError(emitter, "요청이 많습니다. 잠시 후 다시 시도해 주세요.", true);
                }
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            throw new SseSendException(exception);
        }
    }

    private void sendError(SseEmitter emitter, String message, boolean retryable) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message, "retryable", retryable), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception failure) {
            // The client may have disconnected before the terminal event could be delivered.
            if (!(failure instanceof IOException)) emitter.complete();
        }
    }

    private static final class SseSendException extends RuntimeException {
        private SseSendException(Exception cause) {
            super(cause);
        }
    }
}
