package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;
import com.itplace.userapi.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsVerificationServiceImpl implements SmsVerificationService {

    private static final String CODE_PREFIX = "sms:";
    private static final String VERIFIED_PREFIX = "sms:verified:";
    private static final String VERIFICATION_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int VERIFICATION_TEXT_LENGTH = 18;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final OctomoMessageClient octomoMessageClient;

    @Value("${octomo.receiver-phone:1666-3538}")
    private String receiverPhoneNumber;

    @Override
    public SmsVerificationIssueResponse issue(SmsVerificationIssueRequest request) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        if (userRepository.findByPhoneNumber(phoneNumber).isPresent()) {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }

        String verificationText = generateVerificationText();
        redisTemplate.opsForValue().set(codeKey(phoneNumber), verificationText, CODE_TTL);
        log.info(
                "문자 인증 문자열 발급: mobileNumber={}, verificationText='{}', textLength={}, codePoints={}",
                phoneNumber,
                verificationText,
                verificationText.length(),
                toCodePoints(verificationText)
        );

        return SmsVerificationIssueResponse.builder()
                .phoneNumber(phoneNumber)
                .verificationText(verificationText)
                .receiverPhoneNumber(receiverPhoneNumber)
                .expiresInSeconds(CODE_TTL.toSeconds())
                .build();
    }

    @Override
    public void confirm(SmsVerificationConfirmRequest request) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        String verificationText = redisTemplate.opsForValue().get(codeKey(phoneNumber));
        if (verificationText == null) {
            throw new SmsVerificationException(SecurityCode.SMS_CODE_EXPIRED);
        }

        log.info(
                "문자 인증 확인 요청: mobileNumber={}, verificationText='{}', textLength={}, codePoints={}",
                phoneNumber,
                verificationText,
                verificationText.length(),
                toCodePoints(verificationText)
        );

        if (!octomoMessageClient.exists(phoneNumber, verificationText)) {
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        redisTemplate.opsForValue().set(verifiedKey(phoneNumber), "true", VERIFIED_TTL);
        redisTemplate.delete(codeKey(phoneNumber));
    }

    @Override
    public boolean consumeVerified(String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        String key = verifiedKey(normalizedPhoneNumber);
        Boolean verified = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(verified)) {
            redisTemplate.delete(key);
            return true;
        }
        return false;
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

    private String toCodePoints(String value) {
        return value == null ? "" : value.chars()
                .mapToObj(character -> String.format("U+%04X", character))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String codeKey(String phoneNumber) {
        return CODE_PREFIX + phoneNumber;
    }

    private String verifiedKey(String phoneNumber) {
        return VERIFIED_PREFIX + phoneNumber;
    }
}
