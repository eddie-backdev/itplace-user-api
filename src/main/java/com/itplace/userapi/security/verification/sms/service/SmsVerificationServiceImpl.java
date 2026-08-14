package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService.VerificationChannel;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.support.SensitiveDataMasker;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;
import com.itplace.userapi.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsVerificationServiceImpl implements SmsVerificationService {

    private static final String CODE_PREFIX = "sms:";
    private static final String VERIFIED_PREFIX = "sms:verified:";
    private static final String VERIFICATION_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int VERIFICATION_TEXT_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> COMPLETE_VERIFICATION = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1])
                redis.call('SET', KEYS[2], 'true', 'PX', ARGV[2])
                return 1
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final OctomoMessageClient octomoMessageClient;
    private final AuthenticationAbuseProtectionService abuseProtectionService;

    @Value("${octomo.receiver-phone:1666-3538}")
    private String receiverPhoneNumber;

    @Override
    public SmsVerificationIssueResponse issue(SmsVerificationIssueRequest request, String clientAddress) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        abuseProtectionService.checkVerificationIssue(VerificationChannel.SMS, phoneNumber, clientAddress);
        if (userRepository.findByPhoneNumber(phoneNumber).isPresent()) {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }

        String verificationText = generateVerificationText();
        redisTemplate.opsForValue().set(codeKey(phoneNumber), verificationText, CODE_TTL);
        log.info(
                "문자 인증 문자열 발급: mobileNumber={}, textLength={}",
                SensitiveDataMasker.maskPhoneNumber(phoneNumber),
                verificationText.length()
        );

        return SmsVerificationIssueResponse.builder()
                .phoneNumber(phoneNumber)
                .verificationText(verificationText)
                .receiverPhoneNumber(receiverPhoneNumber)
                .expiresInSeconds(CODE_TTL.toSeconds())
                .build();
    }

    @Override
    public void confirm(SmsVerificationConfirmRequest request, String clientAddress) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        abuseProtectionService.checkVerificationConfirm(VerificationChannel.SMS, phoneNumber, clientAddress);
        String verificationText = redisTemplate.opsForValue().get(codeKey(phoneNumber));
        if (verificationText == null) {
            throw new SmsVerificationException(SecurityCode.SMS_CODE_EXPIRED);
        }

        log.info(
                "문자 인증 확인 요청: mobileNumber={}, textLength={}",
                SensitiveDataMasker.maskPhoneNumber(phoneNumber),
                verificationText.length()
        );

        if (!octomoMessageClient.exists(phoneNumber, verificationText)) {
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        Long completed = redisTemplate.execute(
                COMPLETE_VERIFICATION,
                List.of(codeKey(phoneNumber), verifiedKey(phoneNumber)),
                verificationText,
                Long.toString(VERIFIED_TTL.toMillis())
        );
        if (!Long.valueOf(1L).equals(completed)) {
            throw new SmsVerificationException(SecurityCode.SMS_CODE_EXPIRED);
        }
    }

    @Override
    public boolean consumeVerified(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        String key = verifiedKey(normalizedPhoneNumber);
        return "true".equals(redisTemplate.opsForValue().getAndDelete(key));
    }

    private String generateVerificationText() {
        StringBuilder builder = new StringBuilder(VERIFICATION_TEXT_LENGTH);
        for (int index = 0; index < VERIFICATION_TEXT_LENGTH; index++) {
            builder.append(VERIFICATION_ALPHABET.charAt(RANDOM.nextInt(VERIFICATION_ALPHABET.length())));
        }
        return builder.toString();
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
    }

    private String codeKey(String phoneNumber) {
        return CODE_PREFIX + phoneNumber;
    }

    private String verifiedKey(String phoneNumber) {
        return VERIFIED_PREFIX + phoneNumber;
    }
}
