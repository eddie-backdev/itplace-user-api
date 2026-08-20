package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.exception.RecommendationGenerationBusyException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RedisRecommendationGenerationLock implements RecommendationGenerationLock {

    private static final String KEY_PREFIX = "recommendation:generation:";
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(90);
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(100);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration leaseDuration;
    private final Duration waitTimeout;
    private final Duration retryInterval;

    public RedisRecommendationGenerationLock(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_LEASE_DURATION, DEFAULT_WAIT_TIMEOUT, DEFAULT_RETRY_INTERVAL);
    }

    RedisRecommendationGenerationLock(
            StringRedisTemplate redisTemplate,
            Duration leaseDuration,
            Duration waitTimeout,
            Duration retryInterval
    ) {
        this.redisTemplate = redisTemplate;
        this.leaseDuration = leaseDuration;
        this.waitTimeout = waitTimeout;
        this.retryInterval = retryInterval;
    }

    @Override
    public Lease acquire(Long userId) {
        String key = KEY_PREFIX + userId;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTimeout.toNanos();

        while (true) {
            try {
                if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, leaseDuration))) {
                    return new RedisLease(key, token);
                }
            } catch (RuntimeException exception) {
                log.warn("추천 생성 lock 획득 실패: userId={}, reason={}", userId, exception.getMessage());
                throw new RecommendationGenerationBusyException();
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new RecommendationGenerationBusyException();
            }

            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(retryInterval.toNanos(), remainingNanos));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RecommendationGenerationBusyException();
            }
        }
    }

    private void release(String key, String token) {
        try {
            redisTemplate.execute(RELEASE_LOCK, List.of(key), token);
        } catch (RuntimeException exception) {
            log.warn("추천 생성 lock 해제 실패: key={}, reason={}", key, exception.getMessage());
        }
    }

    private final class RedisLease implements Lease {
        private final String key;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedisLease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(key, token);
            }
        }
    }
}
