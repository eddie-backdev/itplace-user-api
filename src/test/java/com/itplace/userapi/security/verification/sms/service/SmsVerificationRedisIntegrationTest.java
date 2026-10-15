package com.itplace.userapi.security.verification.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SmsVerificationRedisIntegrationTest {
    private static final long USER_ID = 904217L;
    private static final String PHONE = "01012345678";
    private static final String OLD_TOKEN = "0123456789ABCDEF0123456789ABCDEF";
    private static final String NEW_TOKEN = "FEDCBA9876543210FEDCBA9876543210";
    private static final String KEY = "sms:user:" + USER_ID;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connectToDisposableRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void closeRedisConnection() {
        connectionFactory.destroy();
    }

    @Test
    void reissueDuringProviderCheckCannotBeDeletedByTheOldCompareAndDelete() throws Exception {
        redis.opsForValue().set(KEY, PHONE + "|" + OLD_TOKEN, Duration.ofMinutes(5));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch allowProviderToReturn = new CountDownLatch(1);
        User user = User.builder().id(USER_ID).role(Role.USER).build();
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, USER_ID)).thenReturn(false);
        OctomoMessageClient provider = org.mockito.Mockito.mock(OctomoMessageClient.class);
        doAnswer(invocation -> {
            providerEntered.countDown();
            if (!allowProviderToReturn.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to resume mocked provider check");
            }
            return true;
        }).when(provider).exists(PHONE, OLD_TOKEN);

        SmsVerificationServiceImpl service = new SmsVerificationServiceImpl(
                redis, userRepository, provider,
                org.mockito.Mockito.mock(AuthenticationAbuseProtectionService.class));
        SmsVerificationConfirmRequest request = new SmsVerificationConfirmRequest();
        request.setPhoneNumber(PHONE);
        request.setVerificationText(OLD_TOKEN);
        AtomicReference<Throwable> confirmationFailure = new AtomicReference<>();
        Thread confirmation = new Thread(() -> {
            try {
                service.confirm(USER_ID, request, "127.0.0.1");
            } catch (Throwable failure) {
                confirmationFailure.set(failure);
            }
        });

        confirmation.start();
        try {
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            redis.opsForValue().set(KEY, PHONE + "|" + NEW_TOKEN, Duration.ofMinutes(5));
        } finally {
            allowProviderToReturn.countDown();
            confirmation.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(confirmation.isAlive()).isFalse();
        assertThat(confirmationFailure.get()).isInstanceOf(SmsVerificationException.class);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo(PHONE + "|" + NEW_TOKEN);
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(user);
    }

    @Test
    void concurrentConfirmationsAtomicallyConsumeChallengeOnlyOnce() throws Exception {
        redis.opsForValue().set(KEY, PHONE + "|" + OLD_TOKEN, Duration.ofMinutes(5));
        CountDownLatch providersEntered = new CountDownLatch(2);
        CountDownLatch releaseProviders = new CountDownLatch(1);
        User user = User.builder().id(USER_ID).role(Role.USER).build();
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, USER_ID)).thenReturn(false);
        OctomoMessageClient provider = org.mockito.Mockito.mock(OctomoMessageClient.class);
        doAnswer(invocation -> {
            providersEntered.countDown();
            if (!releaseProviders.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to resume mocked provider check");
            }
            return true;
        }).when(provider).exists(PHONE, OLD_TOKEN);

        SmsVerificationServiceImpl service = new SmsVerificationServiceImpl(
                redis, userRepository, provider,
                org.mockito.Mockito.mock(AuthenticationAbuseProtectionService.class));
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = confirmationThread(service, firstFailure);
        Thread second = confirmationThread(service, secondFailure);
        first.start();
        second.start();
        try {
            assertThat(providersEntered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProviders.countDown();
            first.join(TimeUnit.SECONDS.toMillis(5));
            second.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(Stream.of(firstFailure.get(), secondFailure.get()).filter(java.util.Objects::isNull))
                .hasSize(1);
        assertThat(Stream.of(firstFailure.get(), secondFailure.get())
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow())
                .isInstanceOf(SmsVerificationException.class);
        verify(userRepository, times(1)).saveAndFlush(user);
        assertThat(redis.opsForValue().get(KEY)).isNull();
    }

    private Thread confirmationThread(SmsVerificationServiceImpl service, AtomicReference<Throwable> failure) {
        SmsVerificationConfirmRequest request = new SmsVerificationConfirmRequest();
        request.setPhoneNumber(PHONE);
        request.setVerificationText(OLD_TOKEN);
        return new Thread(() -> {
            try {
                service.confirm(USER_ID, request, "127.0.0.1");
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
    }
}
