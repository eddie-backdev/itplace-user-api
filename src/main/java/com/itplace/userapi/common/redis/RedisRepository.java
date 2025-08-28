package com.itplace.userapi.common.redis;

import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${spring.redis.ttl.refresh-token}")
    private Duration refreshTokenTtl;

    @Value("${spring.redis.ttl.oauth-temp}")
    private Duration oauthTempTtl;

    // ----------------------------------------------------------------
    // OTP 저장/조회/삭제
    // ----------------------------------------------------------------
    public void saveOtp(String key, String otp, RedisKeyPrefix prefix, Duration duration) {
        redisTemplate.opsForValue().set(prefix.getPrefix() + key, otp, duration);
    }

    public String getOtp(String key, RedisKeyPrefix prefix) {
        return (String) redisTemplate.opsForValue().get(prefix.getPrefix() + key);
    }

    public void deleteOtp(String key, RedisKeyPrefix prefix) {
        redisTemplate.delete(prefix.getPrefix() + key);
    }

    // ----------------------------------------------------------------
    // 1) 리프레시 토큰 저장/조회/삭제
    // ----------------------------------------------------------------

    public void saveRefreshToken(String userId, String refreshToken) {
        redisTemplate.opsForValue()
                .set(RedisKeyPrefix.REFRESH_TOKEN.getPrefix() + userId, refreshToken, refreshTokenTtl);
    }

    public String getRefreshToken(String userId) {
        return (String) redisTemplate.opsForValue().get(RedisKeyPrefix.REFRESH_TOKEN.getPrefix() + userId);
    }

    public void deleteRefreshToken(String userId) {
        redisTemplate.delete(RedisKeyPrefix.REFRESH_TOKEN.getPrefix() + userId);
    }
}
