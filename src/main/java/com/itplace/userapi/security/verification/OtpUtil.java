package com.itplace.userapi.security.verification;

import com.itplace.userapi.common.redis.RedisKeyPrefix;
import com.itplace.userapi.common.redis.RedisRepository;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OtpUtil {

    private static final SecureRandom random = new SecureRandom();

    private final RedisRepository redisRepository;

    @Value("${spring.redis.ttl.otp}")
    private Duration otpTtl;

    private String generateAndCacheOtp(String key, RedisKeyPrefix prefix) {
        String otp = String.format("%06d", random.nextInt(1_000_000));
        redisRepository.saveOtp(key, otp, prefix, otpTtl);
        return otp;
    }

    public String generateSmsOtp(String phoneNumber) {
        return generateAndCacheOtp(phoneNumber, RedisKeyPrefix.OTP_SMS);
    }

    public String generateEmailOtp(String email) {
        return generateAndCacheOtp(email, RedisKeyPrefix.OTP_EMAIL);
    }

    private boolean validateOtp(String key, String otp, RedisKeyPrefix prefix) {
        String storedOtp = redisRepository.getOtp(key, prefix);

        if (storedOtp != null && storedOtp.equals(otp)) {
            redisRepository.deleteOtp(key, prefix); // 인증 성공 시 즉시 삭제
            return true;
        }
        return false;
    }

    public boolean validateSmsOtp(String phoneNumber, String otp) {
        return validateOtp(phoneNumber, otp, RedisKeyPrefix.OTP_SMS);
    }

    public boolean validateEmailOtp(String email, String otp) {
        return validateOtp(email, otp, RedisKeyPrefix.OTP_EMAIL);
    }
}

