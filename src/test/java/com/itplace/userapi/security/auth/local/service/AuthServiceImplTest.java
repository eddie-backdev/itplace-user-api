package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.security.verification.email.service.EmailService;
import com.itplace.userapi.security.verification.sms.service.SmsVerificationService;
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JWTUtil jwtUtil;

    @Mock
    private CookieUtil cookieUtil;

    @Mock
    private SmsVerificationService smsVerificationService;

    @Mock
    private EmailService emailService;

    @Test
    void reissueConvertsRefreshTokenSignatureMismatchToInvalidTokenAndExpiresCookies() {
        AuthServiceImpl authService = authService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setCookies(new Cookie(JWTConstants.CATEGORY_REFRESH, "stale-refresh-token"));
        when(jwtUtil.isExpired("stale-refresh-token"))
                .thenThrow(new SignatureException("JWT signature does not match"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN));

        verify(cookieUtil).expireCookie(response, JWTConstants.CATEGORY_ACCESS);
        verify(cookieUtil).expireCookie(response, JWTConstants.CATEGORY_REFRESH);
    }

    @Test
    void signUpRejectsWhenEmailIsNotVerifiedOnServer() {
        AuthServiceImpl authService = authService();
        SignUpRequest request = signUpRequest();
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumber("01012345678")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOfSatisfying(EmailVerificationException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.EMAIL_VERIFICATION_FAILURE));

        verify(smsVerificationService, never()).consumeVerified(any());
        verify(emailService, never()).consumeVerified(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signUpConsumesVerifiedEmailAfterSmsVerification() {
        AuthServiceImpl authService = authService();
        SignUpRequest request = signUpRequest();
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumber("01012345678")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(true);
        when(smsVerificationService.consumeVerified("01012345678")).thenReturn(true);
        when(emailService.consumeVerified("hong@example.com")).thenReturn(true);
        when(passwordEncoder.encode("!Password123")).thenReturn("encoded-password");

        authService.signUp(request);

        verify(emailService).consumeVerified("hong@example.com");
        verify(userRepository).save(any(User.class));
    }

    private AuthServiceImpl authService() {
        return new AuthServiceImpl(
                redisTemplate,
                passwordEncoder,
                userRepository,
                jwtUtil,
                cookieUtil,
                smsVerificationService,
                emailService
        );
    }

    private SignUpRequest signUpRequest() {
        return SignUpRequest.builder()
                .nickname("홍길동")
                .email("hong@example.com")
                .password("!Password123")
                .passwordConfirm("!Password123")
                .phoneNumber("01012345678")
                .gender(Gender.MALE)
                .carrier(Carrier.SKT)
                .membershipGradeCode(Grade.SKT_VIP)
                .birthday(LocalDate.of(1990, 1, 1))
                .build();
    }
}
