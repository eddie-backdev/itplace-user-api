package com.itplace.userapi.security.verification.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SmsVerificationServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OctomoMessageClient octomoMessageClient;

    @InjectMocks
    private SmsVerificationServiceImpl smsVerificationService;

    @Test
    void issueCreatesCodeForUserOriginatedSmsToOctomoNumber() {
        ReflectionTestUtils.setField(smsVerificationService, "receiverPhoneNumber", "1666-3538");
        SmsVerificationIssueRequest request = new SmsVerificationIssueRequest();
        request.setPhoneNumber("01012345678");
        when(userRepository.findByPhoneNumber("01012345678")).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SmsVerificationIssueResponse response = smsVerificationService.issue(request);

        assertThat(response.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(response.getReceiverPhoneNumber()).isEqualTo("1666-3538");
        assertThat(response.getVerificationText()).matches("[A-HJ-NP-Z2-9]{6}");
        verify(valueOperations).set(eq("sms:01012345678"), eq(response.getVerificationText()), any(Duration.class));
    }

    @Test
    void confirmChecksOctomoForTheIssuedCodeAndMarksPhoneVerified() {
        SmsVerificationConfirmRequest request = new SmsVerificationConfirmRequest();
        request.setPhoneNumber("01012345678");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("sms:01012345678")).thenReturn("AB7K9Q");
        when(octomoMessageClient.exists("01012345678", "AB7K9Q")).thenReturn(true);

        smsVerificationService.confirm(request);

        verify(valueOperations).set(eq("sms:verified:01012345678"), eq("true"), any(Duration.class));
        verify(redisTemplate).delete("sms:01012345678");
    }

    @Test
    void confirmRejectsWhenNoIssuedCodeExists() {
        SmsVerificationConfirmRequest request = new SmsVerificationConfirmRequest();
        request.setPhoneNumber("01012345678");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("sms:01012345678")).thenReturn(null);

        assertThatThrownBy(() -> smsVerificationService.confirm(request))
                .isInstanceOf(SmsVerificationException.class);
    }
}
