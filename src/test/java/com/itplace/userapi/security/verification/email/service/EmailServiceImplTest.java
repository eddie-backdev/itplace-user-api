package com.itplace.userapi.security.verification.email.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.verification.email.dto.request.EmailConfirmRequest;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender javaMailSender;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OtpUtil otpUtil;

    @InjectMocks
    private EmailServiceImpl emailService;

    @Test
    void confirmMarksEmailVerifiedAfterOtpValidation() {
        EmailConfirmRequest request = confirmRequest();
        when(otpUtil.validateEmailOtp("hong@example.com", "123456")).thenReturn(true);
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        emailService.confirm(request);

        verify(valueOperations).set(eq("email:verified:hong@example.com"), eq("true"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void consumeVerifiedDeletesVerifiedEmailMarker() {
        when(redisTemplate.hasKey("email:verified:hong@example.com")).thenReturn(true);

        boolean consumed = emailService.consumeVerified("hong@example.com");

        assertThat(consumed).isTrue();
        verify(redisTemplate).delete("email:verified:hong@example.com");
    }

    @Test
    void consumeVerifiedReturnsFalseWhenEmailWasNotVerified() {
        when(redisTemplate.hasKey("email:verified:hong@example.com")).thenReturn(false);

        boolean consumed = emailService.consumeVerified("hong@example.com");

        assertThat(consumed).isFalse();
    }

    private EmailConfirmRequest confirmRequest() {
        EmailConfirmRequest request = new EmailConfirmRequest();
        request.setEmail("hong@example.com");
        request.setVerificationCode("123456");
        return request;
    }
}
