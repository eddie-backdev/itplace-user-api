package com.itplace.userapi.security.abuse;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.AuthenticationRateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationAbuseProtectionService {

    private static final String KEY_PREFIX = "security:abuse:";

    private final RedisRateLimitStore rateLimitStore;
    private final AuthenticationAbuseProtectionProperties properties;

    public void checkVerificationIssue(
            VerificationChannel channel,
            String identifier,
            String clientAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String identifierHash = fingerprint(normalizeIdentifier(channel, identifier));
        String clientHash = fingerprint(normalizeClientAddress(clientAddress));
        String namespace = KEY_PREFIX + "verification:" + channel.key() + ":issue:";

        if (!rateLimitStore.tryAcquire(
                namespace + "cooldown:" + identifierHash,
                properties.getVerificationIssueCooldown()
        )) {
            reject("verification_issue_cooldown", identifierHash, clientHash);
        }

        enforceLimit(
                namespace + "identifier:" + identifierHash,
                properties.getVerificationIssueWindow(),
                properties.getVerificationIssuePerIdentifier(),
                "verification_issue_identifier",
                identifierHash,
                clientHash
        );
        enforceLimit(
                namespace + "ip:" + clientHash,
                properties.getVerificationIssueWindow(),
                properties.getVerificationIssuePerIp(),
                "verification_issue_ip",
                identifierHash,
                clientHash
        );
    }

    public void checkVerificationConfirm(
            VerificationChannel channel,
            String identifier,
            String clientAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String identifierHash = fingerprint(normalizeIdentifier(channel, identifier));
        String clientHash = fingerprint(normalizeClientAddress(clientAddress));
        String namespace = KEY_PREFIX + "verification:" + channel.key() + ":confirm:";

        enforceLimit(
                namespace + "identifier:" + identifierHash,
                properties.getVerificationConfirmWindow(),
                properties.getVerificationConfirmPerIdentifier(),
                "verification_confirm_identifier",
                identifierHash,
                clientHash
        );
        enforceLimit(
                namespace + "ip:" + clientHash,
                properties.getVerificationConfirmWindow(),
                properties.getVerificationConfirmPerIp(),
                "verification_confirm_ip",
                identifierHash,
                clientHash
        );
    }

    public boolean isLoginAllowed(String email, String clientAddress) {
        if (!properties.isEnabled()) {
            return true;
        }

        String identifierHash = fingerprint(normalizeEmail(email));
        String clientHash = fingerprint(normalizeClientAddress(clientAddress));
        long ipRequests = rateLimitStore.increment(
                KEY_PREFIX + "login:ip:" + clientHash,
                properties.getLoginIpWindow()
        );
        if (ipRequests > properties.getLoginPerIp()) {
            log.warn("로그인 요청 제한: reason=login_ip, identifierHash={}, clientHash={}", identifierHash, clientHash);
            return false;
        }

        boolean blocked = rateLimitStore.exists(loginBlockKey(identifierHash));
        if (blocked) {
            log.warn("로그인 요청 제한: reason=login_failure_backoff, identifierHash={}, clientHash={}",
                    identifierHash, clientHash);
        }
        return !blocked;
    }

    public void recordLoginFailure(String email) {
        if (!properties.isEnabled() || !StringUtils.hasText(email)) {
            return;
        }

        String identifierHash = fingerprint(normalizeEmail(email));
        long failures = rateLimitStore.increment(
                loginFailureKey(identifierHash),
                properties.getLoginFailureWindow()
        );
        if (failures < properties.getLoginFailureThreshold()) {
            return;
        }

        Duration blockDuration = calculateBlockDuration(failures);
        rateLimitStore.extendBlock(loginBlockKey(identifierHash), blockDuration);
        log.warn("로그인 실패 백오프 적용: identifierHash={}, failures={}, blockSeconds={}",
                identifierHash, failures, blockDuration.toSeconds());
    }

    public void clearLoginFailures(String email) {
        if (!properties.isEnabled() || !StringUtils.hasText(email)) {
            return;
        }

        String identifierHash = fingerprint(normalizeEmail(email));
        rateLimitStore.delete(List.of(loginFailureKey(identifierHash), loginBlockKey(identifierHash)));
    }

    private void enforceLimit(
            String key,
            Duration window,
            int limit,
            String reason,
            String identifierHash,
            String clientHash
    ) {
        if (rateLimitStore.increment(key, window) > limit) {
            reject(reason, identifierHash, clientHash);
        }
    }

    private void reject(String reason, String identifierHash, String clientHash) {
        log.warn("인증 요청 제한: reason={}, identifierHash={}, clientHash={}", reason, identifierHash, clientHash);
        throw new AuthenticationRateLimitException(SecurityCode.AUTHENTICATION_RATE_LIMITED);
    }

    private Duration calculateBlockDuration(long failures) {
        long exponent = Math.min(failures - properties.getLoginFailureThreshold(), 30L);
        long multiplier = 1L << exponent;
        long initialMillis = properties.getLoginInitialLock().toMillis();
        long maxMillis = properties.getLoginMaxLock().toMillis();
        long requestedMillis;
        try {
            requestedMillis = Math.multiplyExact(initialMillis, multiplier);
        } catch (ArithmeticException ignored) {
            requestedMillis = maxMillis;
        }
        return Duration.ofMillis(Math.min(requestedMillis, maxMillis));
    }

    private String loginFailureKey(String identifierHash) {
        return KEY_PREFIX + "login:failure:" + identifierHash;
    }

    private String loginBlockKey(String identifierHash) {
        return KEY_PREFIX + "login:block:" + identifierHash;
    }

    private String normalizeIdentifier(VerificationChannel channel, String identifier) {
        return channel == VerificationChannel.EMAIL ? normalizeEmail(identifier) : normalizeValue(identifier);
    }

    private String normalizeEmail(String email) {
        return normalizeValue(email).toLowerCase(Locale.ROOT);
    }

    private String normalizeClientAddress(String clientAddress) {
        return StringUtils.hasText(clientAddress) ? clientAddress.trim() : "unknown";
    }

    private String normalizeValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public enum VerificationChannel {
        EMAIL("email"),
        SMS("sms");

        private final String key;

        VerificationChannel(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
