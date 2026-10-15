package com.itplace.userapi.security.verification.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService.VerificationChannel;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.AuthenticationRateLimitException;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SmsVerificationServiceImplTest {

    private static final String PHONE = "01012345678";
    private static final String CHALLENGE_KEY = "sms:user:7";
    private static final String TOKEN = "0123456789ABCDEF0123456789ABCDEF";
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OctomoMessageClient octomoMessageClient;

    @Mock
    private AuthenticationAbuseProtectionService abuseProtectionService;

    @InjectMocks
    private SmsVerificationServiceImpl smsVerificationService;

    @Test
    void issueStoresOneHundredTwentyEightBitTokenOnTheAuthenticatedUsersChallengeWithTtl() {
        ReflectionTestUtils.setField(smsVerificationService, "receiverPhoneNumber", "1666-3538");
        givenUser(7L);
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SmsVerificationIssueResponse response = smsVerificationService.issue(
                7L, issueRequest(), "127.0.0.1");

        assertThat(response.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(response.getVerificationText()).matches("[0-9A-F]{32}");
        assertThat(response.getReceiverPhoneNumber()).isEqualTo("1666-3538");
        assertThat(response.getExpiresInSeconds()).isEqualTo(300);
        verify(abuseProtectionService).checkVerificationIssue(VerificationChannel.SMS, PHONE, "127.0.0.1");
        ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(CHALLENGE_KEY), challenge.capture(), eq(CHALLENGE_TTL));
        assertThat(challenge.getValue()).isEqualTo(PHONE + "|" + response.getVerificationText());
    }

    @Test
    void reissueOverwritesTheSingleAccountChallengeAndInvalidatesTheOldToken() {
        givenUser(7L);
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SmsVerificationIssueResponse oldChallenge = smsVerificationService.issue(7L, issueRequest(), "127.0.0.1");
        SmsVerificationIssueResponse newChallenge = smsVerificationService.issue(7L, issueRequest(), "127.0.0.1");

        assertThat(newChallenge.getVerificationText()).isNotEqualTo(oldChallenge.getVerificationText());
        verify(valueOperations, org.mockito.Mockito.times(2)).set(
                eq(CHALLENGE_KEY), any(String.class), eq(CHALLENGE_TTL));
    }

    @Test
    void confirmRequiresExactAuthenticatedUserPhoneAndTokenChallengeThenPersistsPhone() {
        User user = givenUser(7L);
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(octomoMessageClient.exists(PHONE, TOKEN)).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq(challenge))).thenReturn(1L);

        smsVerificationService.confirm(7L, confirmRequest(PHONE, TOKEN), "127.0.0.1");

        verify(abuseProtectionService).checkVerificationConfirm(VerificationChannel.SMS, PHONE, "127.0.0.1");
        verify(octomoMessageClient).exists(PHONE, TOKEN);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(CHALLENGE_KEY)), eq(challenge));
        verify(userRepository).saveAndFlush(user);
        assertThat(user.getPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    void anotherAuthenticatedUserCannotConsumeChallengeEvenWithItsPhoneAndToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("sms:user:8")).thenReturn(null);

        assertThatThrownBy(() -> smsVerificationService.confirm(
                8L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class)
                .extracting("code")
                .isEqualTo(SecurityCode.SMS_CODE_EXPIRED);

        verify(octomoMessageClient, never()).exists(any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void phoneOrTokenMismatchCannotReachOctomoOrConsumeChallenge() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(PHONE + "|" + TOKEN);

        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest("01087654321", TOKEN), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class);
        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest(PHONE, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class);

        verify(octomoMessageClient, never()).exists(any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void expiredChallengeFailsBeforeAnyProviderCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class)
                .extracting("code")
                .isEqualTo(SecurityCode.SMS_CODE_EXPIRED);
        verify(octomoMessageClient, never()).exists(any(), any());
    }

    @Test
    void providerMustConfirmTheSameMobileOriginatedPhoneAndToken() {
        User user = givenUser(7L);
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(octomoMessageClient.exists(PHONE, TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class)
                .extracting("code")
                .isEqualTo(SecurityCode.SMS_VERIFICATION_FAILURE);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicatePhoneIsRejectedBeforeProviderAndChallengeConsumption() {
        givenUser(7L);
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(true);

        assertThatThrownBy(() -> smsVerificationService.issue(7L, issueRequest(), "127.0.0.1"))
                .isInstanceOf(DuplicatePhoneNumberException.class);

        verify(redisTemplate, never()).opsForValue();
        verify(octomoMessageClient, never()).exists(any(), any());
    }

    @Test
    void concurrentDuplicatePhoneRaceReturnsDuplicatePhoneError() {
        User user = givenUser(7L);
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(octomoMessageClient.exists(PHONE, TOKEN)).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq(challenge))).thenReturn(1L);
        when(userRepository.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("unique phone conflict"));

        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(DuplicatePhoneNumberException.class);
    }

    @Test
    void issueRateLimitRejectionDoesNotLookUpUserOrWriteChallenge() {
        doThrow(new AuthenticationRateLimitException(SecurityCode.AUTHENTICATION_RATE_LIMITED))
                .when(abuseProtectionService).checkVerificationIssue(VerificationChannel.SMS, PHONE, "127.0.0.1");

        assertThatThrownBy(() -> smsVerificationService.issue(7L, issueRequest(), "127.0.0.1"))
                .isInstanceOf(AuthenticationRateLimitException.class);

        verify(userRepository, never()).findById(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void issueRejectsMissingUserBeforeChallengeWrite() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> smsVerificationService.issue(7L, issueRequest(), "127.0.0.1"))
                .isInstanceOf(com.itplace.userapi.user.exception.UserNotFoundException.class);

        verify(userRepository, never()).existsByPhoneNumberAndIdNot(any(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void confirmLeavesChallengeUnconsumedWhenProviderCallFails() {
        User user = givenUser(7L);
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(octomoMessageClient.exists(PHONE, TOKEN)).thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> smsVerificationService.confirm(7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(user.getPhoneNumber()).isNull();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmFailsClosedWhenChallengeStoreCannotBeRead() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> smsVerificationService.confirm(7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);

        verify(octomoMessageClient, never()).exists(any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmRejectsMissingUserWithoutProviderCallOrChallengeConsumption() {
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> smsVerificationService.confirm(7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(com.itplace.userapi.user.exception.UserNotFoundException.class);

        verify(octomoMessageClient, never()).exists(any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void changedRedisChallengeCannotBeConsumedAfterOctomoCheck() {
        User user = givenUser(7L);
        String challenge = PHONE + "|" + TOKEN;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CHALLENGE_KEY)).thenReturn(challenge);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndIdNot(PHONE, 7L)).thenReturn(false);
        when(octomoMessageClient.exists(PHONE, TOKEN)).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq(challenge))).thenReturn(0L);

        assertThatThrownBy(() -> smsVerificationService.confirm(
                7L, confirmRequest(PHONE, TOKEN), "127.0.0.1"))
                .isInstanceOf(SmsVerificationException.class)
                .extracting("code")
                .isEqualTo(SecurityCode.SMS_VERIFICATION_FAILURE);
        verify(userRepository, never()).saveAndFlush(any());
    }

    private User givenUser(Long userId) {
        User user = User.builder().id(userId).role(Role.USER).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }

    private SmsVerificationIssueRequest issueRequest() {
        SmsVerificationIssueRequest request = new SmsVerificationIssueRequest();
        request.setPhoneNumber(PHONE);
        return request;
    }

    private SmsVerificationConfirmRequest confirmRequest(String phone, String token) {
        SmsVerificationConfirmRequest request = new SmsVerificationConfirmRequest();
        request.setPhoneNumber(phone);
        request.setVerificationText(token);
        return request;
    }
}
