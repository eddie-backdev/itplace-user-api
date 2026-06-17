package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.request.KakaoCodeRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoLoginResult;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoUserProfile;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.exception.InvalidMembershipProfileException;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthServiceImpl implements OAuthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final AuthCredentialRepository authCredentialRepository;
    private final JWTUtil jwtUtil;
    private final KakaoOAuthProviderClient kakaoOAuthProviderClient;

    @Override
    @Transactional
    public KakaoLoginResult processKakaoLogin(KakaoCodeRequest request) {
        KakaoUserProfile profile = kakaoOAuthProviderClient.fetchUser(request.getCode(), request.getRedirectUri());

        return authCredentialRepository.findByTypeAndProviderAndProviderUserId(AuthCredentialType.OAUTH, "kakao", profile.providerId())
                .map(credential -> KakaoLoginResult.builder()
                        .isExistingUser(true)
                        .authResult(createAuthResultForUser(credential.getUser()))
                        .build())
                .orElseGet(() -> KakaoLoginResult.builder()
                        .isExistingUser(false)
                        .tempToken(jwtUtil.createTempJwt("kakao", profile.providerId()))
                        .build());
    }

    @Override
    @Transactional
    public OAuthResult signUpWithOAuth(String tempToken, OAuthSignUpRequest request) {
        Claims claims = getVerifiedClaims(tempToken);
        String provider = claims.get("provider", String.class);
        String providerId = claims.get("providerId", String.class);

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
        }

        User user = createNewSocialUser(request, provider, providerId);

        return createAuthResultForUser(user);
    }

    @Override
    @Transactional
    public OAuthResult linkOAuthAccount(String tempToken, OAuthLinkRequest request) {
        Claims claims = getVerifiedClaims(tempToken);
        String provider = claims.get("provider", String.class);
        String providerId = claims.get("providerId", String.class);

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        AuthCredential localCredential = authCredentialRepository.findByUser_IdAndType(user.getId(), AuthCredentialType.LOCAL_PASSWORD)
                .orElseThrow(() -> new InvalidCredentialsException(SecurityCode.UNAUTHORIZED_ACCESS));

        if (!passwordEncoder.matches(request.getPassword(), localCredential.getPasswordHash())) {
            throw new InvalidCredentialsException(SecurityCode.UNAUTHORIZED_ACCESS);
        }

        linkOAuthCredential(user, provider, providerId);
        return createAuthResultForUser(user);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse result(PrincipalDetails principalDetails) {
        User user = userRepository.findById(principalDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        return buildLoginResponse(user);
    }

    private Claims getVerifiedClaims(String tempToken) {
        if (jwtUtil.isExpired(tempToken) || !"temp".equals(jwtUtil.getCategory(tempToken))) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
        }
        return jwtUtil.getClaims(tempToken);
    }

    private User createNewSocialUser(OAuthSignUpRequest request, String provider, String providerId) {
        if (!MembershipProfileValidator.isValid(request.getCarrier(), request.getMembershipGradeCode())) {
            throw new InvalidMembershipProfileException(UserCode.INVALID_MEMBERSHIP_PROFILE);
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .gender(request.getGender())
                .birthday(request.getBirthday())
                .carrier(request.getCarrier())
                .membershipGradeCode(request.getMembershipGradeCode())
                .membershipVerified(false)
                .role(Role.USER)
                .build();

        user.getAuthCredentials().add(AuthCredential.oauth(user, provider, providerId));

        return userRepository.save(user);
    }

    private User linkOAuthCredential(User user, String provider, String providerId) {
        boolean alreadyLinked = authCredentialRepository.existsByUser_IdAndTypeAndProviderAndProviderUserId(
                user.getId(),
                AuthCredentialType.OAUTH,
                provider,
                providerId
        );

        if (!alreadyLinked) {
            user.getAuthCredentials().add(AuthCredential.oauth(user, provider, providerId));
        }

        return user;
    }

    private OAuthResult createAuthResultForUser(User user) {
        String role = user.getRole().getKey();
        String accessToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_ACCESS);
        String refreshToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_REFRESH);
        redisTemplate.opsForValue().set("RT:" + user.getId(), refreshToken, jwtUtil.getRefreshTokenValidityInMS(), TimeUnit.MILLISECONDS);

        LoginResponse loginResponse = buildLoginResponse(user);

        return OAuthResult.builder()
                .loginResponse(loginResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private LoginResponse buildLoginResponse(User user) {
        return LoginResponse.builder()
                .name(user.getName())
                .carrier(user.getCarrier())
                .membershipGradeCode(user.getMembershipGradeCode())
                .membershipGrade(user.getMembershipGradeCode())
                .membershipVerified(Boolean.TRUE.equals(user.getMembershipVerified()))
                .build();
    }
}
