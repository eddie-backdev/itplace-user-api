package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.recommend.exception.RecommendationGenerationBusyException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisRecommendationGenerationLockTest {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(10);
    private static final String LOCK_KEY = "recommendation:generation:7";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void component_usesRedisTemplateConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(StringRedisTemplate.class, () -> redisTemplate);
            context.register(RedisRecommendationGenerationLock.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(RecommendationGenerationLock.class))
                    .isInstanceOf(RedisRecommendationGenerationLock.class);
        }
    }

    @Test
    void acquire_releasesOnlyOnceWithOwnerToken() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LEASE_DURATION))).thenReturn(true);
        RedisRecommendationGenerationLock lock = lock(Duration.ofSeconds(1));

        RecommendationGenerationLock.Lease lease = lock.acquire(7L);
        lease.close();
        lease.close();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(LOCK_KEY), token.capture(), eq(LEASE_DURATION));
        verify(redisTemplate, times(1)).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(LOCK_KEY)),
                eq(token.getValue())
        );
    }

    @Test
    void acquire_throwsBusyExceptionWhenWaitTimeoutExpires() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LEASE_DURATION))).thenReturn(false);
        RedisRecommendationGenerationLock lock = lock(Duration.ZERO);

        assertThatThrownBy(() -> lock.acquire(7L))
                .isInstanceOf(RecommendationGenerationBusyException.class);
    }

    @Test
    void acquire_mapsRedisFailureToBusyException() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LEASE_DURATION)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedisRecommendationGenerationLock lock = lock(Duration.ZERO);

        assertThatThrownBy(() -> lock.acquire(7L))
                .isInstanceOf(RecommendationGenerationBusyException.class);
    }

    @Test
    void close_doesNotFailRequestWhenRedisReleaseFails() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LEASE_DURATION))).thenReturn(true);
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(LOCK_KEY)),
                any()
        )).thenThrow(new IllegalStateException("redis unavailable"));
        RedisRecommendationGenerationLock lock = lock(Duration.ZERO);
        RecommendationGenerationLock.Lease lease = lock.acquire(7L);

        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    private RedisRecommendationGenerationLock lock(Duration waitTimeout) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return new RedisRecommendationGenerationLock(
                redisTemplate,
                LEASE_DURATION,
                waitTimeout,
                Duration.ofMillis(1)
        );
    }
}
