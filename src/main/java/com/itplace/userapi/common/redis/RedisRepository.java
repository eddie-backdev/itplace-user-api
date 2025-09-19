package com.itplace.userapi.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisRepository {

    private final String SmsPrefix = "sms:";           // 기존 SMS
    private final String RefreshPrefix = "refresh:";   // 리프레시 토큰
    private final String TempPrefix = "oauth:temp:";   // OAuth 임시 프로필

    // SMS
    private final int SMS_LIMIT = 3 * 60;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void createSmsCertification(String userPhone, String certCode) {
        redis.opsForValue().set(SmsPrefix + userPhone, certCode, Duration.ofSeconds(SMS_LIMIT));
    }

    public String getSmsCertification(String userPhone) {
        return redis.opsForValue().get(SmsPrefix + userPhone);
    }

    public void deleteSmsCertification(String userPhone) {
        redis.delete(SmsPrefix + userPhone);
    }

    public boolean hasSmsKey(String userPhone) {
        return redis.hasKey(SmsPrefix + userPhone);
    }

    // ----------------------------------------------------------------
    // 1) 리프레시 토큰 저장/조회/삭제
    // ----------------------------------------------------------------

    /**
     * userId 기준으로 리프레시 토큰을 저장 (만료: 7일 예시)
     */
    public void saveRefreshToken(String userId, String refreshToken) {
        redis.opsForValue().set(RefreshPrefix + userId, refreshToken, Duration.ofDays(7));
    }

    /**
     * userId 로 저장된 리프레시 토큰 조회
     */
    public String getRefreshToken(String userId) {
        return redis.opsForValue().get(RefreshPrefix + userId);
    }

    /**
     * userId 로 저장된 리프레시 토큰 삭제
     */
    public void deleteRefreshToken(String userId) {
        redis.delete(RefreshPrefix + userId);
    }

    // ----------------------------------------------------------------
    // 2) OAuth 임시 프로필 저장/조회/삭제
    // ----------------------------------------------------------------

    /**
     * tempId 기준으로 OAuth 프로필(Map)을 JSON 으로 직렬화해 저장 (만료: 10분 예시)
     */
    public void saveTempOAuth2User(String tempId, Map<String, Object> attributes) {
        try {
            String json = objectMapper.writeValueAsString(attributes);
            redis.opsForValue().set(TempPrefix + tempId, json, Duration.ofMinutes(10));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("OAuth2 user 직렬화 실패", e);
        }
    }

    /**
     * tempId 로 저장된 JSON 을 Map 으로 역직렬화해 반환
     */
    public Map<String, Object> getTempOAuth2User(String tempId) {
        try {
            String json = redis.opsForValue().get(TempPrefix + tempId);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("OAuth2 user 역직렬화 실패", e);
        }
    }

    /**
     * tempId 로 저장된 OAuth 프로필 삭제
     */
    public void deleteTempOAuth2User(String tempId) {
        redis.delete(TempPrefix + tempId);
    }
}
