package com.itplace.userapi.security.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService.VerificationChannel;
import com.itplace.userapi.security.exception.AuthenticationRateLimitException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthenticationAbuseProtectionServiceTest {

    @Mock
    private RedisRateLimitStore rateLimitStore;

    private AuthenticationAbuseProtectionProperties properties;
    private AuthenticationAbuseProtectionService service;

    @BeforeEach
    void setUp() {
        properties = new AuthenticationAbuseProtectionProperties();
        service = new AuthenticationAbuseProtectionService(rateLimitStore, properties);
    }

    @Test
    void verificationIssueRejectsRequestDuringIdentifierCooldown() {
        when(rateLimitStore.tryAcquire(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(false);

        assertThatThrownBy(() -> service.checkVerificationIssue(
                VerificationChannel.EMAIL,
                "Hong@Example.com",
                "127.0.0.1"
        ))
                .isInstanceOfSatisfying(AuthenticationRateLimitException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.AUTHENTICATION_RATE_LIMITED));

        verify(rateLimitStore, never()).increment(anyString(), eq(Duration.ofHours(1)));
    }

    @Test
    void verificationIssueRejectsIdentifierAfterHourlyLimit() {
        when(rateLimitStore.tryAcquire(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(true);
        when(rateLimitStore.increment(anyString(), eq(Duration.ofHours(1)))).thenReturn(6L);

        assertThatThrownBy(() -> service.checkVerificationIssue(
                VerificationChannel.SMS,
                "01012345678",
                "127.0.0.1"
        )).isInstanceOf(AuthenticationRateLimitException.class);
    }

    @Test
    void smsPollingCanContinueThroughoutFiveMinuteVerificationWithoutEmailAttemptLimit() {
        when(rateLimitStore.tryAcquire(anyString(), eq(Duration.ofSeconds(5)))).thenReturn(true);
        when(rateLimitStore.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(12L);

        for (int seconds = 0; seconds < 300; seconds += 5) {
            service.checkVerificationConfirm(VerificationChannel.SMS, "01012345678", "127.0.0.1");
        }

        verify(rateLimitStore, times(60)).tryAcquire(anyString(), eq(Duration.ofSeconds(5)));
        verify(rateLimitStore, never()).increment(anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void smsDuplicatePollReturnsRemainingCooldownWithoutSpendingIpBudget() {
        when(rateLimitStore.tryAcquire(anyString(), eq(Duration.ofSeconds(5)))).thenReturn(false);
        when(rateLimitStore.retryAfterSeconds(anyString(), eq(Duration.ofSeconds(5)))).thenReturn(3L);

        assertThatThrownBy(() -> service.checkVerificationConfirm(
                VerificationChannel.SMS, "01012345678", "127.0.0.1"))
                .isInstanceOfSatisfying(AuthenticationRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(3));
        verify(rateLimitStore, never()).increment(anyString(), eq(Duration.ofMinutes(1)));
    }

    @Test
    void smsIpLimitReturnsRemainingWindow() {
        when(rateLimitStore.tryAcquire(anyString(), eq(Duration.ofSeconds(5)))).thenReturn(true);
        when(rateLimitStore.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(121L);
        when(rateLimitStore.retryAfterSeconds(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(37L);

        assertThatThrownBy(() -> service.checkVerificationConfirm(
                VerificationChannel.SMS, "01012345678", "127.0.0.1"))
                .isInstanceOfSatisfying(AuthenticationRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(37));
    }

    @Test
    void emailStillRejectsEleventhConfirmationInTenMinutes() {
        when(rateLimitStore.increment(anyString(), eq(Duration.ofMinutes(10)))).thenReturn(11L);

        assertThatThrownBy(() -> service.checkVerificationConfirm(
                VerificationChannel.EMAIL, "user@example.com", "127.0.0.1"))
                .isInstanceOf(AuthenticationRateLimitException.class);
        verify(rateLimitStore, never()).tryAcquire(anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void loginIsRejectedWhileFailureBackoffIsActive() {
        when(rateLimitStore.increment(anyString(), eq(Duration.ofMinutes(1)))).thenReturn(1L);
        when(rateLimitStore.exists(anyString())).thenReturn(true);

        assertThat(service.isLoginAllowed("hong@example.com", "127.0.0.1")).isFalse();
    }

    @Test
    void loginFailureBackoffDoublesAndStopsAtConfiguredMaximum() {
        when(rateLimitStore.increment(anyString(), eq(Duration.ofMinutes(15))))
                .thenReturn(5L, 6L, 20L);

        service.recordLoginFailure("hong@example.com");
        service.recordLoginFailure("hong@example.com");
        service.recordLoginFailure("hong@example.com");

        verify(rateLimitStore).extendBlock(anyString(), eq(Duration.ofSeconds(30)));
        verify(rateLimitStore).extendBlock(anyString(), eq(Duration.ofMinutes(1)));
        verify(rateLimitStore).extendBlock(anyString(), eq(Duration.ofMinutes(15)));
    }
}
