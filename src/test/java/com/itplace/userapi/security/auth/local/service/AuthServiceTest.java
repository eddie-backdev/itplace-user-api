package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
    private EmailService emailService;
    @Mock
    private MembershipProfileValidator membershipProfileValidator;

    @Test
    void reissueConvertsRefreshTokenSignatureMismatchToInvalidTokenAndExpiresCookies() {
        AuthService authService = authService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setCookies(new Cookie(JWTConstants.CATEGORY_REFRESH, "stale-refresh-token"));
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("stale-refresh-token"));
        when(jwtUtil.getClaims("stale-refresh-token"))
                .thenThrow(new SignatureException("JWT signature does not match"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN));

        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueConvertsExpiredRefreshTokenAndExpiresCookies() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("expired-token"));
        when(jwtUtil.getClaims("expired-token")).thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.REFRESH_TOKEN_EXPIRED));

        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueRejectsMissingRefreshCookieAndExpiresAuthCookies() {
        AuthService authService = authService();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.reissue(new MockHttpServletRequest(), response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.REFRESH_TOKEN_REQUIRE));

        verify(cookieUtil).expireAuthCookies(response);
        verify(jwtUtil, never()).getClaims(any());
    }

    @Test
    void reissueRejectsAccessTokenPresentedAsRefreshAndExpiresAuthCookies() {
        AuthService authService = authService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setCookies(new Cookie(JWTConstants.CATEGORY_REFRESH, "access-token"));
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("access-token"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("access-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_ACCESS);

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN_TYPE));

        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueRejectsRefreshTokenWithMissingIdentityClaim() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("refresh-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("refresh-token"));
        when(jwtUtil.getClaims("refresh-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN));

        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueRejectsRefreshTokenWithMissingRoleClaim() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("refresh-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("refresh-token"));
        when(jwtUtil.getClaims("refresh-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenReturn("refresh-token");

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.INVALID_TOKEN));

        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueCreatesAccessTokenOnlyForSavedRefreshTokenWithRequiredClaims() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("refresh-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("refresh-token"));
        when(jwtUtil.getClaims("refresh-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(claims.get(JWTConstants.CLAIM_ROLE, String.class)).thenReturn("ROLE_USER");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenReturn("refresh-token");
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS)).thenReturn("access-token");

        authService.reissue(request, response);

        verify(jwtUtil).createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS);
        verify(cookieUtil).setAccessTokenCookie(response, "access-token");
    }

    @Test
    void reissueRejectsRefreshTokenThatIsNotTheSavedSession() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("presented-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("presented-token"));
        when(jwtUtil.getClaims("presented-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenReturn("different-token");

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueDoesNotMintAccessTokenWhenRefreshSessionIsMissing() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("presented-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("presented-token"));
        when(jwtUtil.getClaims("presented-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenReturn(null);

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void reissueDoesNotMintAccessTokenWhenRefreshSessionStoreFails() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("presented-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("presented-token"));
        when(jwtUtil.getClaims("presented-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOf(IllegalStateException.class);

        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void reissueRejectsNonpositiveIdentityAndUnknownRoleClaims() {
        AuthService authService = authService();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest badIdentityRequest = requestWithRefreshCookie("bad-identity");
        Claims badIdentityClaims = org.mockito.Mockito.mock(Claims.class);
        when(cookieUtil.getCookieValue(badIdentityRequest, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("bad-identity"));
        when(jwtUtil.getClaims("bad-identity")).thenReturn(badIdentityClaims);
        when(badIdentityClaims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(badIdentityClaims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(0L);

        assertThatThrownBy(() -> authService.reissue(badIdentityRequest, response))
                .isInstanceOf(InvalidCredentialsException.class);

        MockHttpServletRequest request = requestWithRefreshCookie("bad-role");
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("bad-role"));
        when(jwtUtil.getClaims("bad-role")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenReturn(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:7")).thenReturn("bad-role");
        when(claims.get(JWTConstants.CLAIM_ROLE, String.class)).thenReturn("ROOT");

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void reissueRejectsWrongTypeIdentityClaimWithoutMintingAccessToken() {
        AuthService authService = authService();
        MockHttpServletRequest request = requestWithRefreshCookie("wrong-type-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH))
                .thenReturn(Optional.of("wrong-type-token"));
        when(jwtUtil.getClaims("wrong-type-token")).thenReturn(claims);
        when(claims.get(JWTConstants.CLAIM_CATEGORY, String.class)).thenReturn(JWTConstants.CATEGORY_REFRESH);
        when(claims.get(JWTConstants.CLAIM_USER_ID, Long.class)).thenThrow(new ClassCastException("not a Long"));

        assertThatThrownBy(() -> authService.reissue(request, response))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(cookieUtil).expireAuthCookies(response);
    }

    @Test
    void signUpRejectsWhenEmailIsNotVerifiedOnServer() {
        AuthService authService = authService();
        SignUpRequest request = signUpRequest();
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOfSatisfying(EmailVerificationException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SecurityCode.EMAIL_VERIFICATION_FAILURE));

        verify(emailService, never()).consumeVerified(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signupOnlyConsumesEmailVerificationAndSavesNoUnverifiedPhone() {
        AuthService authService = authService();
        SignUpRequest request = signUpRequest();
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(true);
        when(emailService.consumeVerified("hong@example.com")).thenReturn(true);
        when(passwordEncoder.encode("!Password123")).thenReturn("encoded-password");

        authService.signUp(request);

        verify(emailService).consumeVerified("hong@example.com");
        org.mockito.ArgumentCaptor<User> savedUser = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPhoneNumber()).isNull();
    }

    @Test
    void signupPersistsMvnoWithoutMembershipGrade() {
        SignUpRequest request = signUpRequest();
        request.setCarrier(Carrier.MVNO);
        request.setMembershipGradeCode(null);
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(true);
        when(emailService.consumeVerified("hong@example.com")).thenReturn(true);
        when(passwordEncoder.encode("!Password123")).thenReturn("encoded-password");

        authService().signUp(request);

        org.mockito.ArgumentCaptor<User> savedUser = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getCarrier()).isEqualTo(Carrier.MVNO);
        assertThat(savedUser.getValue().getMembershipGradeCode()).isNull();
    }

    @Test
    void signupIgnoresLegacyPhoneNumberRequestField() throws Exception {
        SignUpRequest request = new ObjectMapper().registerModule(new JavaTimeModule()).readValue("""
                {
                  "nickname":"홍길동",
                  "email":"hong@example.com",
                  "password":"!Password123",
                  "passwordConfirm":"!Password123",
                  "phoneNumber":"01012345678",
                  "gender":"MALE",
                  "carrier":"SKT",
                  "membershipGradeCode":"SKT_VIP",
                  "birthday":"1990-01-01"
                }
                """, SignUpRequest.class);
        AuthService authService = authService();
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(true);
        when(emailService.consumeVerified("hong@example.com")).thenReturn(true);
        when(passwordEncoder.encode("!Password123")).thenReturn("encoded-password");

        authService.signUp(request);

        org.mockito.ArgumentCaptor<User> savedUser = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPhoneNumber()).isNull();
    }

    @Test
    void signupPasswordMismatchDoesNotCheckEmailOrConsumeVerification() {
        SignUpRequest request = signUpRequest();
        request.setPasswordConfirm("different-password");

        assertThatThrownBy(() -> authService().signUp(request))
                .isInstanceOf(com.itplace.userapi.security.exception.PasswordMismatchException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(emailService, never()).hasVerified(any());
        verify(emailService, never()).consumeVerified(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signupDuplicateEmailDoesNotConsumeVerificationOrSave() {
        when(userRepository.findByEmail("hong@example.com"))
                .thenReturn(Optional.of(User.builder().id(9L).build()));

        assertThatThrownBy(() -> authService().signUp(signUpRequest()))
                .isInstanceOf(com.itplace.userapi.security.exception.DuplicateEmailException.class);

        verify(emailService, never()).hasVerified(any());
        verify(emailService, never()).consumeVerified(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signupDoesNotSaveWhenVerifiedEmailMarkerWasAlreadyConsumed() {
        when(userRepository.findByEmail("hong@example.com")).thenReturn(Optional.empty());
        when(emailService.hasVerified("hong@example.com")).thenReturn(true);
        when(emailService.consumeVerified("hong@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService().signUp(signUpRequest()))
                .isInstanceOf(EmailVerificationException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    private AuthService authService() {
        org.mockito.Mockito.lenient().when(membershipProfileValidator.isValidForSignUp(
                org.mockito.ArgumentMatchers.nullable(Carrier.class), org.mockito.ArgumentMatchers.nullable(Grade.class))).thenReturn(true);
        return new AuthService(
                redisTemplate,
                passwordEncoder,
                userRepository,
                jwtUtil,
                cookieUtil,
                emailService,
                membershipProfileValidator
        );
    }

    private SignUpRequest signUpRequest() {
        return SignUpRequest.builder()
                .nickname("홍길동")
                .email("hong@example.com")
                .password("!Password123")
                .passwordConfirm("!Password123")
                .gender(Gender.MALE)
                .carrier(Carrier.SKT)
                .membershipGradeCode(Grade.SKT_VIP)
                .birthday(LocalDate.of(1990, 1, 1))
                .build();
    }

    private MockHttpServletRequest requestWithRefreshCookie(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JWTConstants.CATEGORY_REFRESH, token));
        return request;
    }
}
