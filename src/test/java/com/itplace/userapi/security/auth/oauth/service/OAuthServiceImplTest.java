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
    private KakaoOAuthProviderClient kakaoOAuthProviderClient;

    @InjectMocks
    private OAuthServiceImpl oAuthService;

    @Test
    void processKakaoLoginIssuesSessionForExistingOAuthCredential() {
        KakaoCodeRequest request = kakaoRequest();
        User user = User.builder()
                .id(7L)
                .name("홍길동")
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipVerified(true)
                .role(Role.USER)
                .build();
        AuthCredential credential = AuthCredential.oauth(user, "kakao", "kakao-1");

        when(kakaoOAuthProviderClient.fetchUser("auth-code", "itplace://oauth/callback/kakao"))
                .thenReturn(new KakaoUserProfile("kakao-1", "hong@example.com"));
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
        assertThat(result.getAuthResult().getLoginResponse().getName()).isEqualTo("홍길동");
        verify(valueOperations).set("RT:7", "refresh-token", 86_400_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void processKakaoLoginReturnsTempTokenForNewOAuthCredential() {
        KakaoCodeRequest request = kakaoRequest();
        when(kakaoOAuthProviderClient.fetchUser("auth-code", "itplace://oauth/callback/kakao"))
                .thenReturn(new KakaoUserProfile("kakao-2", null));
        when(authCredentialRepository.findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH,
                "kakao",
                "kakao-2"
        )).thenReturn(Optional.empty());
        when(jwtUtil.createTempJwt("kakao", "kakao-2")).thenReturn("temp-token");

        KakaoLoginResult result = oAuthService.processKakaoLogin(request);

        assertThat(result.isExistingUser()).isFalse();
        assertThat(result.getTempToken()).isEqualTo("temp-token");
        verify(redisTemplate, never()).opsForValue();
        verify(jwtUtil, never()).createJwt(any(), any(), any());
    }

    @Test
    void signUpWithOAuthRejectsExistingEmailInsteadOfLinkingAccount() {
        OAuthSignUpRequest request = new OAuthSignUpRequest();
        request.setName("김카카오");
        request.setEmail("owner@example.com");
        request.setGender(Gender.MALE);
        request.setBirthday(LocalDate.of(1990, 1, 1));
        request.setCarrier(Carrier.LGU);
        request.setMembershipGradeCode(Grade.VIP);
        User existingUser = User.builder().id(99L).email("owner@example.com").role(Role.USER).build();

        mockTempClaims("attacker-kakao");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> oAuthService.signUpWithOAuth("temp-token", request))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any(User.class));
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

        mockTempClaims("attacker-kakao");
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

        mockTempClaims("kakao-linked");
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
                .name("기존회원")
                .email("owner@example.com")
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipVerified(true)
                .role(Role.USER)
                .build();
        existingUser.getAuthCredentials().add(AuthCredential.localPassword(existingUser, "encoded-password"));

        mockTempClaims("kakao-linked");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(existingUser));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(existingUser.getAuthCredentials().get(0)));
        when(authCredentialRepository.existsByUser_IdAndTypeAndProviderAndProviderUserId(
                7L,
                AuthCredentialType.OAUTH,
                "kakao",
                "kakao-linked"
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
                    assertThat(credential.getProviderUserId()).isEqualTo("kakao-linked");
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

    private void mockTempClaims(String providerId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);

        when(jwtUtil.isExpired("temp-token")).thenReturn(false);
        when(jwtUtil.getCategory("temp-token")).thenReturn("temp");
        when(jwtUtil.getClaims("temp-token")).thenReturn(claims);
        when(claims.get("provider", String.class)).thenReturn("kakao");
        when(claims.get("providerId", String.class)).thenReturn(providerId);
    }
}
