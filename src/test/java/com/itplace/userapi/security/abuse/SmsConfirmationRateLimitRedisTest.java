package com.itplace.userapi.security.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService.VerificationChannel;
import com.itplace.userapi.security.exception.AuthenticationRateLimitException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SmsConfirmationRateLimitRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4").withExposedPorts(6379);
    private LettuceConnectionFactory factory;
    private RedisRateLimitStore store;
    private AuthenticationAbuseProtectionService service;
    private AuthenticationAbuseProtectionProperties properties;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        store = new RedisRateLimitStore(new StringRedisTemplate(factory));
        properties = new AuthenticationAbuseProtectionProperties();
        service = new AuthenticationAbuseProtectionService(store, properties);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void duplicateIsBlockedAndSamePhoneCanResumeAfterFiveSeconds() throws Exception {
        confirm("01012345678");
        assertThatThrownBy(() -> confirm("01012345678"))
                .isInstanceOfSatisfying(AuthenticationRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isBetween(1L, 5L));
        Thread.sleep(5100);
        assertThatCode(() -> confirm("01012345678")).doesNotThrowAnyException();
    }

    @Test
    void distinctPhonesStillShareIpLimitAndReceiveRemainingWindow() {
        properties.setSmsConfirmPerIp(2);
        confirm("01012345678");
        confirm("01012345679");
        assertThatThrownBy(() -> confirm("01012345670"))
                .isInstanceOfSatisfying(AuthenticationRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void retryAfterRoundsUpAndHandlesMissingKeys() {
        store.tryAcquire("fractional-cooldown", Duration.ofMillis(2500));
        assertThat(store.retryAfterSeconds("fractional-cooldown", Duration.ofSeconds(5))).isEqualTo(3);
        assertThat(store.retryAfterSeconds("missing", Duration.ofSeconds(5))).isEqualTo(5);
    }

    private void confirm(String phoneNumber) {
        service.checkVerificationConfirm(VerificationChannel.SMS, phoneNumber, "127.0.0.1");
    }
}
