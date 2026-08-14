package com.itplace.userapi.security.abuse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisRateLimitStore {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private static final DefaultRedisScript<Long> EXTEND_BLOCK = new DefaultRedisScript<>("""
            local currentTtl = redis.call('PTTL', KEYS[1])
            local requestedTtl = tonumber(ARGV[1])
            if currentTtl < requestedTtl then
                redis.call('SET', KEYS[1], '1', 'PX', requestedTtl)
                return requestedTtl
            end
            return currentTtl
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public boolean tryAcquire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }

    public long increment(String key, Duration window) {
        Long count = redisTemplate.execute(
                INCREMENT_WITH_EXPIRY,
                List.of(key),
                Long.toString(window.toMillis())
        );
        return count == null ? 0L : count;
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void extendBlock(String key, Duration duration) {
        redisTemplate.execute(EXTEND_BLOCK, List.of(key), Long.toString(duration.toMillis()));
    }

    public void delete(Collection<String> keys) {
        redisTemplate.delete(keys);
    }
}
