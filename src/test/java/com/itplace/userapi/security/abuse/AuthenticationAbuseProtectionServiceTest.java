package com.itplace.userapi.security.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
