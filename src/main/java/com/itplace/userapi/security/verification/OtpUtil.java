package com.itplace.userapi.security.verification;

import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OtpUtil {

    private static final String SMS_PREFIX = "sms:";
    private static final String EMAIL_PREFIX = "email:";
    private static final SecureRandom random = new SecureRandom();

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

    public String generateSmsOtp(String phoneNumber) {
        return generateAndCacheOtp(phoneNumber, SMS_PREFIX);
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
        String storedOtp = redisTemplate.opsForValue().get(redisKey);

        if (storedOtp != null && storedOtp.equals(otp)) {
            redisTemplate.delete(redisKey); // 인증 성공 시 즉시 삭제
            return true;
        }
        return false;
    }

    public boolean validateSmsOtp(String phoneNumber, String otp) {
        return validateOtp(phoneNumber, otp, SMS_PREFIX);
    }

    public boolean validateEmailOtp(String email, String otp) {
        return validateOtp(email, otp, EMAIL_PREFIX);
    }
}

