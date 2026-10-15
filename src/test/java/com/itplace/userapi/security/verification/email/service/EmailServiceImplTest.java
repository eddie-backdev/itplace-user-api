package com.itplace.userapi.security.verification.email.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService;
import com.itplace.userapi.security.abuse.AuthenticationAbuseProtectionService.VerificationChannel;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.verification.email.dto.request.EmailConfirmRequest;
import com.itplace.userapi.security.verification.email.dto.request.EmailVerificationRequest;
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
import org.springframework.mail.MailSendException;

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

    @Mock
    private AuthenticationAbuseProtectionService abuseProtectionService;

    @InjectMocks
    private EmailServiceImpl emailService;

    @Test
    void confirmMarksEmailVerifiedAfterOtpValidation() {
        EmailConfirmRequest request = confirmRequest();
        when(otpUtil.validateEmailOtp("hong@example.com", "123456")).thenReturn(true);
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        emailService.confirm(request, "127.0.0.1");

        verify(abuseProtectionService).checkVerificationConfirm(
                VerificationChannel.EMAIL,
                "hong@example.com",
                "127.0.0.1"
        );
        verify(valueOperations).set(eq("email:verified:hong@example.com"), eq("true"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void confirmRejectsInvalidOtpWithoutCreatingVerifiedMarker() {
        EmailConfirmRequest request = confirmRequest();
        when(otpUtil.validateEmailOtp("hong@example.com", "123456")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> emailService.confirm(request, "127.0.0.1"))
                .isInstanceOf(EmailVerificationException.class);

        verify(userRepository, never()).findByEmail("hong@example.com");
        verify(valueOperations, never()).set(
                eq("email:verified:hong@example.com"), eq("true"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void confirmRejectsAlreadyRegisteredEmailWithoutCreatingVerifiedMarker() {
        EmailConfirmRequest request = confirmRequest();
        when(otpUtil.validateEmailOtp("hong@example.com", "123456")).thenReturn(true);
        when(userRepository.findByEmail("hong@example.com"))
                .thenReturn(Optional.of(com.itplace.userapi.user.entity.User.builder().id(1L).build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> emailService.confirm(request, "127.0.0.1"))
                .isInstanceOf(com.itplace.userapi.security.exception.DuplicateEmailException.class);

        verify(valueOperations, never()).set(
                eq("email:verified:hong@example.com"), eq("true"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void emailProviderFailureDeletesOtpAndDoesNotCreateVerifiedMarker() {
        EmailVerificationRequest request = new EmailVerificationRequest();
        request.setEmail("hong@example.com");
        jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        when(otpUtil.generateEmailOtp("hong@example.com")).thenReturn("123456");
        doThrow(new MailSendException("mail provider unavailable")).when(javaMailSender).send(message);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> emailService.send(request, "127.0.0.1"))
                .isInstanceOf(EmailVerificationException.class);

        verify(redisTemplate).delete("email:hong@example.com");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void consumeVerifiedDeletesVerifiedEmailMarker() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("email:verified:hong@example.com")).thenReturn("true");

        boolean consumed = emailService.consumeVerified("hong@example.com");

        assertThat(consumed).isTrue();
        verify(valueOperations).getAndDelete("email:verified:hong@example.com");
    }

    @Test
    void consumeVerifiedReturnsFalseWhenEmailWasNotVerified() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("email:verified:hong@example.com")).thenReturn(null);

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
