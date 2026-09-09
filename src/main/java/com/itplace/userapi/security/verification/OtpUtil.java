package com.itplace.userapi.security.verification;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OtpUtil {

    private static final String EMAIL_PREFIX = "email:";
    private static final String ATTEMPT_SUFFIX = ":attempts";
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final SecureRandom random = new SecureRandom();
    private static final DefaultRedisScript<Long> VALIDATE_OTP = new DefaultRedisScript<>("""
            local attempts = tonumber(redis.call('GET', KEYS[2]) or '0')
            if attempts >= tonumber(ARGV[2]) then
                return -1
            end

            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end

            local newAttempts = redis.call('INCR', KEYS[2])
            if newAttempts == 1 then
                redis.call('PEXPIRE', KEYS[2], ARGV[3])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    //    @Value("${otp.ttl.minutes}")
    private static final long ttlInMinutes = 3;

    /**
     * 지정된 키(전화번호 또는 이메일)에 대한 OTP를 생성하고 Redis에 저장합니다.
     *
     * @param key    전화번호 또는 이메일 주소
     * @param prefix 키의 네임스페이스를 구분하기 위한 접두사
     * @return 생성된 6자리 OTP
     */
    public String generateAndCacheOtp(String key, String prefix) {
        String otp = String.format("%06d", random.nextInt(1_000_000));
        String redisKey = prefix + key;
        redisTemplate.opsForValue().set(redisKey, otp, Duration.ofMinutes(ttlInMinutes));
        return otp;
    }


    public String generateEmailOtp(String email) {
        return generateAndCacheOtp(email, EMAIL_PREFIX);
    }

    /**
     * 주어진 키와 OTP가 유효한지 검증합니다.
     *
     * @param key    전화번호 또는 이메일 주소
     * @param otp    사용자가 입력한 OTP
     * @param prefix 키의 네임스페이스를 구분하기 위한 접두사
     * @return 유효하면 true, 아니면 false
     */
    public boolean validateOtp(String key, String otp, String prefix) {
        String redisKey = prefix + key;
        String attemptKey = redisKey + ATTEMPT_SUFFIX;
        Long result = redisTemplate.execute(
                VALIDATE_OTP,
                List.of(redisKey, attemptKey),
                otp,
                Integer.toString(MAX_OTP_ATTEMPTS),
                Long.toString(Duration.ofMinutes(ttlInMinutes).toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }


    public boolean validateEmailOtp(String email, String otp) {
        return validateOtp(email, otp, EMAIL_PREFIX);
    }
}
