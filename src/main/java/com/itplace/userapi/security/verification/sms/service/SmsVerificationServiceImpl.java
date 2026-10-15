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
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsVerificationServiceImpl implements SmsVerificationService {

    private static final String CHALLENGE_PREFIX = "sms:user:";
    private static final int VERIFICATION_TEXT_BYTES = 16;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CONSUME_CHALLENGE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
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
    public SmsVerificationIssueResponse issue(Long userId, SmsVerificationIssueRequest request, String clientAddress) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        abuseProtectionService.checkVerificationIssue(VerificationChannel.SMS, phoneNumber, clientAddress);
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        if (userRepository.existsByPhoneNumberAndIdNot(phoneNumber, userId)) {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }

        String verificationText = generateVerificationText();
        redisTemplate.opsForValue().set(challengeKey(userId), challengeValue(phoneNumber, verificationText), CODE_TTL);
        log.info(
                "계정 휴대폰 인증 문자열 발급: userId={}, mobileNumber={}, textLength={}",
                userId,
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
    @Transactional
    public void confirm(Long userId, SmsVerificationConfirmRequest request, String clientAddress) {
        String phoneNumber = normalizePhoneNumber(request.getPhoneNumber());
        abuseProtectionService.checkVerificationConfirm(VerificationChannel.SMS, phoneNumber, clientAddress);
        String expectedChallenge = challengeValue(phoneNumber, request.getVerificationText());
        String activeChallenge = redisTemplate.opsForValue().get(challengeKey(userId));
        if (activeChallenge == null) {
            throw new SmsVerificationException(SecurityCode.SMS_CODE_EXPIRED);
        }
        if (!expectedChallenge.equals(activeChallenge)) {
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        if (userRepository.existsByPhoneNumberAndIdNot(phoneNumber, userId)) {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }

        log.info(
                "계정 휴대폰 인증 확인 요청: userId={}, mobileNumber={}, textLength={}",
                userId,
                SensitiveDataMasker.maskPhoneNumber(phoneNumber),
                request.getVerificationText().length()
        );

        if (!octomoMessageClient.exists(phoneNumber, request.getVerificationText())) {
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        Long consumed = redisTemplate.execute(
                CONSUME_CHALLENGE,
                List.of(challengeKey(userId)),
                expectedChallenge
        );
        if (!Long.valueOf(1L).equals(consumed)) {
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        user.setPhoneNumber(phoneNumber);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }
    }

    private String generateVerificationText() {
        byte[] randomBytes = new byte[VERIFICATION_TEXT_BYTES];
        RANDOM.nextBytes(randomBytes);
        return HexFormat.of().withUpperCase().formatHex(randomBytes);
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
    }

    private String challengeKey(Long userId) {
        return CHALLENGE_PREFIX + userId;
    }

    private String challengeValue(String phoneNumber, String verificationText) {
        return phoneNumber + "|" + verificationText;
    }
}
