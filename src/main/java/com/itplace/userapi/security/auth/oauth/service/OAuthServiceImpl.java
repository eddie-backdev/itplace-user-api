package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthLinkRequest;
import com.itplace.userapi.security.auth.oauth.dto.request.OAuthSignUpRequest;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.Membership;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.SocialAccount;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import java.util.UUID;
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
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JWTUtil jwtUtil;

    @Override
    @Transactional
    public OAuthResult signUpWithOAuth(String tempToken, OAuthSignUpRequest request) {
        Claims claims = getVerifiedClaims(tempToken);
        String provider = claims.get("provider", String.class);
        String providerId = claims.get("providerId", String.class);

        // 신규 가입이므로, 해당 휴대폰 번호로 가입된 유저가 없어야 함
        userRepository.findByPhoneNumber(request.getPhoneNumber()).ifPresent(u -> {
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        });

        String membershipId = request.getMembershipId();
        if (membershipId != null && membershipId.isEmpty()) {
            membershipId = null;
        }

        User user = User.builder()
                .name(request.getName())
                .phoneNumber(request.getPhoneNumber())
                .gender(request.getGender())
                .birthday(request.getBirthday())
                .membershipId(membershipId)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(Role.USER)
                .build();

        user.getSocialAccounts().add(SocialAccount.builder()
                .provider(provider).providerId(providerId).user(user).build());

        userRepository.save(user);

        return createAuthResultForUser(user);
    }

    @Override
    @Transactional
    public OAuthResult linkOAuthAccount(String tempToken, OAuthLinkRequest request) {
        Claims claims = getVerifiedClaims(tempToken);
        String provider = claims.get("provider", String.class);
        String providerId = claims.get("providerId", String.class);

        // 계정 연동이므로, 해당 휴대폰 번호로 가입된 유저가 반드시 있어야 함
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        boolean alreadyLinked = user.getSocialAccounts().stream()
                .anyMatch(sa -> sa.getProvider().equals(provider) && sa.getProviderId().equals(providerId));

        if (!alreadyLinked) {
            user.getSocialAccounts().add(SocialAccount.builder()
                    .provider(provider).providerId(providerId).user(user).build());
        }

        return createAuthResultForUser(user);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse result(PrincipalDetails principalDetails) {
        User user = userRepository.findById(principalDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        String name = user.getName();
        String membershipId = user.getMembershipId();
        Grade membershipGrade = null;

        if (membershipId != null) {
            Optional<Membership> membershipOpt = membershipRepository.findById(membershipId);
            if (membershipOpt.isPresent()) {
                membershipGrade = membershipOpt.get().getGrade();
            }
        }

        return LoginResponse.builder()
                .name(name)
                .membershipGrade(membershipGrade)
                .build();
    }

    private Claims getVerifiedClaims(String tempToken) {
        if (jwtUtil.isExpired(tempToken) || !"temp".equals(jwtUtil.getCategory(tempToken))) {
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
        }
        return jwtUtil.getClaims(tempToken);
    }

    private OAuthResult createAuthResultForUser(User user) {
        String role = user.getRole().getKey();
        String accessToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_ACCESS);
        String refreshToken = jwtUtil.createJwt(user.getId(), role, JWTConstants.CATEGORY_REFRESH);
        redisTemplate.opsForValue().set("RT:" + user.getId(), refreshToken, jwtUtil.getRefreshTokenValidityInMS(), TimeUnit.MILLISECONDS);

        LoginResponse loginResponse = LoginResponse.builder()
                .name(user.getName())
                .membershipGrade(getMembershipGrade(user.getMembershipId()))
                .build();

        return OAuthResult.builder()
                .loginResponse(loginResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private Grade getMembershipGrade(String membershipId) {
        if (membershipId == null) {
            return null;
        }
        return membershipRepository.findByMembershipId(membershipId)
                .map(Membership::getGrade)
                .orElse(null);
    }
}