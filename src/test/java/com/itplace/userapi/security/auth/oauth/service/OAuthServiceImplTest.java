package com.itplace.userapi.security.auth.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.security.auth.oauth.dto.request.KakaoCodeRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoLoginResult;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoUserProfile;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import io.jsonwebtoken.Claims;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class OAuthServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthCredentialRepository authCredentialRepository;

    @Mock
    private JWTUtil jwtUtil;

    @Mock
    private MembershipProfileValidator membershipProfileValidator;

    @Mock
    private KakaoOAuthProviderClient kakaoOAuthProviderClient;

    @InjectMocks
    private OAuthServiceImpl oAuthService;

    @Test
    void processKakaoLoginIssuesSessionForExistingOAuthCredential() {
        KakaoCodeRequest request = kakaoRequest();
        User user = User.builder()
                .id(7L)
                .nickname("홍길동")
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipVerified(true)
                .role(Role.USER)
                .build();
        AuthCredential credential = AuthCredential.oauth(user, "kakao", "kakao-1");

        when(kakaoOAuthProviderClient.fetchUser("auth-code", "itplace://oauth/callback/kakao"))
                .thenReturn(new KakaoUserProfile("kakao-1", "hong@example.com", "홍길동", true, true));
        when(authCredentialRepository.findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH,
                "kakao",
                "kakao-1"
        )).thenReturn(Optional.of(credential));
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS)).thenReturn("access-token");
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_REFRESH)).thenReturn("refresh-token");
        when(jwtUtil.getRefreshTokenValidityInMS()).thenReturn(86_400_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        KakaoLoginResult result = oAuthService.processKakaoLogin(request);

        assertThat(result.isExistingUser()).isTrue();
        assertThat(result.getAuthResult().getAccessToken()).isEqualTo("access-token");
        assertThat(result.getAuthResult().getLoginResponse().getNickname()).isEqualTo("홍길동");
        verify(valueOperations).set("RT:7", "refresh-token", 86_400_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void processKakaoLoginReturnsTempTokenForNewOAuthCredential() {
        KakaoCodeRequest request = kakaoRequest();
        when(kakaoOAuthProviderClient.fetchUser("auth-code", "itplace://oauth/callback/kakao"))
                .thenReturn(new KakaoUserProfile("kakao-2", "new@example.com", "카카오닉", true, true));
        when(authCredentialRepository.findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH,
                "kakao",
                "kakao-2"
        )).thenReturn(Optional.empty());
        when(jwtUtil.createTempJwt("kakao", "kakao-2", "new@example.com", "카카오닉")).thenReturn("temp-token");

        KakaoLoginResult result = oAuthService.processKakaoLogin(request);

        assertThat(result.isExistingUser()).isFalse();
        assertThat(result.getTempToken()).isEqualTo("temp-token");
        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void providerFailureDoesNotLookUpOrCreateCredentialsOrSession() {
        when(kakaoOAuthProviderClient.fetchUser("auth-code", "itplace://oauth/callback/kakao"))
                .thenThrow(new InvalidCredentialsException(com.itplace.userapi.security.SecurityCode.INVALID_INPUT_VALUE));

        assertThatThrownBy(() -> oAuthService.processKakaoLogin(kakaoRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(authCredentialRepository, never()).findByTypeAndProviderAndProviderUserId(any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(jwtUtil, never()).createTempJwt(any(), any(), any(), any());
    }

    @Test
    void signUpWithOAuthRejectsExistingEmailInsteadOfLinkingAccount() {
        OAuthSignUpRequest request = new OAuthSignUpRequest();
        request.setNickname("김카카오");
        request.setGender(Gender.MALE);
        request.setBirthday(LocalDate.of(1990, 1, 1));
        request.setCarrier(Carrier.LGU);
        request.setMembershipGradeCode(Grade.VIP);
        User existingUser = User.builder().id(99L).email("owner@example.com").role(Role.USER).build();

        mockTempClaims("123");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("temp-token", request))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void signUpWithOAuthPersistsMvnoWithoutMembershipGrade() {
        OAuthSignUpRequest request = validOAuthSignUpRequest();
        request.setCarrier(Carrier.MVNO);
        request.setMembershipGradeCode(null);
        when(membershipProfileValidator.isValidForSignUp(Carrier.MVNO, null)).thenReturn(true);
        User savedUser = User.builder()
                .id(7L)
                .nickname("신규회원")
                .email("owner@example.com")
                .carrier(Carrier.MVNO)
                .role(Role.USER)
                .build();
        mockTempClaims("123");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS)).thenReturn("access-token");
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_REFRESH)).thenReturn("refresh-token");
        when(jwtUtil.getRefreshTokenValidityInMS()).thenReturn(86_400_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OAuthResult result = oAuthService.signUpWithOAuth("temp-token", request);

        org.mockito.ArgumentCaptor<User> captured = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captured.capture());
        assertThat(captured.getValue().getCarrier()).isEqualTo(Carrier.MVNO);
        assertThat(captured.getValue().getMembershipGradeCode()).isNull();
        assertThat(result.getLoginResponse().getCarrier()).isEqualTo(Carrier.MVNO);
        assertThat(result.getLoginResponse().getMembershipGradeCode()).isNull();
    }

    @Test
    void signUpWithOAuthRejectsWrongTemporaryTokenCategoryBeforeAccountLookup() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("category", String.class)).thenReturn("access");

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("temp-token", new OAuthSignUpRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void signUpWithOAuthMapsMalformedTemporaryTokenToInvalidCredentials() {
        when(jwtUtil.getClaims("malformed-token")).thenThrow(new IllegalArgumentException("malformed"));

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("malformed-token", new OAuthSignUpRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void signUpWithOAuthRejectsMissingProviderIdentityBeforeSaving() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("category", String.class)).thenReturn("temp");
        when(claims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(claims.get("provider", String.class)).thenReturn("kakao");

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("temp-token", validOAuthSignUpRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void linkOAuthAccountMapsWrongTypedProviderIdToInvalidToken() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("category", String.class)).thenReturn("temp");
        when(claims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(claims.get("provider", String.class)).thenReturn("kakao");
        when(claims.get("providerId", String.class)).thenThrow(new ClassCastException("not a string"));

        assertThatThrownBy(() -> oAuthService.linkOAuthAccount("temp-token", new OAuthLinkRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void signUpWithOAuthMapsWrongTypedEmailClaimToInvalidToken() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("category", String.class)).thenReturn("temp");
        when(claims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(claims.get("provider", String.class)).thenReturn("kakao");
        when(claims.get("providerId", String.class)).thenReturn("123");
        when(claims.get("email", String.class)).thenThrow(new ClassCastException("not a string"));

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("temp-token", validOAuthSignUpRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void linkOAuthAccountRejectsWrongProviderIdentityBeforeAccountLookup() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("category", String.class)).thenReturn("temp");
        when(claims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(claims.get("provider", String.class)).thenReturn("google");

        assertThatThrownBy(() -> oAuthService.linkOAuthAccount("temp-token", new OAuthLinkRequest()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).findByEmail(any());
        verify(authCredentialRepository, never()).findByUser_IdAndType(any(), any());
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void linkOAuthAccountRejectsWrongLocalPassword() {
        OAuthLinkRequest request = new OAuthLinkRequest();
        request.setEmail("owner@example.com");
        request.setPassword("wrong-password");
        User existingUser = User.builder()
                .id(7L)
                .email("owner@example.com")
                .role(Role.USER)
                .build();
        AuthCredential localCredential = AuthCredential.localPassword(existingUser, "encoded-password");

        mockTempClaims("123");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(localCredential));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> oAuthService.linkOAuthAccount("temp-token", request))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void linkOAuthAccountRejectsOAuthOnlyAccountWithoutLocalPasswordCredential() {
        OAuthLinkRequest request = new OAuthLinkRequest();
        request.setEmail("owner@example.com");
        request.setPassword("local-password");
        User existingUser = User.builder()
                .id(7L)
                .email("owner@example.com")
                .role(Role.USER)
                .build();

        mockTempClaims("456");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthService.linkOAuthAccount("temp-token", request))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(jwtUtil, never()).createJwt(any(), any(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void linkOAuthAccountAddsOAuthCredentialAfterPasswordVerification() {
        OAuthLinkRequest request = new OAuthLinkRequest();
        request.setEmail("owner@example.com");
        request.setPassword("local-password");
        User existingUser = User.builder()
                .id(7L)
                .nickname("기존회원")
                .email("owner@example.com")
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipVerified(true)
                .role(Role.USER)
                .build();
        existingUser.getAuthCredentials().add(AuthCredential.localPassword(existingUser, "encoded-password"));

        mockTempClaims("456");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(existingUser.getAuthCredentials().get(0)));
        when(authCredentialRepository.existsByUser_IdAndTypeAndProviderAndProviderUserId(
                7L,
                AuthCredentialType.OAUTH,
                "kakao",
                "456"
        )).thenReturn(false);
        when(passwordEncoder.matches("local-password", "encoded-password")).thenReturn(true);
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_ACCESS)).thenReturn("access-token");
        when(jwtUtil.createJwt(7L, "ROLE_USER", JWTConstants.CATEGORY_REFRESH)).thenReturn("refresh-token");
        when(jwtUtil.getRefreshTokenValidityInMS()).thenReturn(86_400_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OAuthResult result = oAuthService.linkOAuthAccount("temp-token", request);

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(existingUser.getAuthCredentials())
                .filteredOn(credential -> credential.getType() == AuthCredentialType.OAUTH)
                .singleElement()
                .satisfies(credential -> {
                    assertThat(credential.getProvider()).isEqualTo("kakao");
                    assertThat(credential.getProviderUserId()).isEqualTo("456");
                    assertThat(credential.getUser()).isSameAs(existingUser);
                });
        verify(valueOperations).set("RT:7", "refresh-token", 86_400_000L, TimeUnit.MILLISECONDS);
    }

    private KakaoCodeRequest kakaoRequest() {
        KakaoCodeRequest request = new KakaoCodeRequest();
        request.setCode("auth-code");
        request.setRedirectUri("itplace://oauth/callback/kakao");
        return request;
    }

    private OAuthSignUpRequest validOAuthSignUpRequest() {
        OAuthSignUpRequest request = new OAuthSignUpRequest();
        request.setNickname("신규회원");
        request.setGender(Gender.MALE);
        request.setBirthday(LocalDate.of(1990, 1, 1));
        request.setCarrier(Carrier.LGU);
        request.setMembershipGradeCode(Grade.VIP);
        return request;
    }

    private void mockTempClaims(String providerId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);

        when(claims.get("category", String.class)).thenReturn("temp");
        when(claims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("provider", String.class)).thenReturn("kakao");
        when(claims.get("providerId", String.class)).thenReturn(providerId);
        when(claims.get("email", String.class)).thenReturn("owner@example.com");
    }
}
