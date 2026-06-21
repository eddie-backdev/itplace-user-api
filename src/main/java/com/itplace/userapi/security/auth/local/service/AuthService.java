package com.itplace.userapi.security.auth.local.service;

import static com.itplace.userapi.security.auth.local.filter.LoginFilter.REFRESH_TOKEN_PREFIX;

import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.email.service.EmailService;
import com.itplace.userapi.security.verification.sms.service.SmsVerificationService;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.exception.InvalidMembershipProfileException;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JWTUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final SmsVerificationService smsVerificationService;
    private final EmailService emailService;

    public void reissue(HttpServletRequest request, HttpServletResponse response) {
        log.info("Access 토큰 재발급 시작");

        try {
            String newAccessToken = reissueAccessToken(readRefreshToken(request));
            cookieUtil.setAccessTokenCookie(response, newAccessToken);
        } catch (InvalidCredentialsException e) {
            cookieUtil.expireAuthCookies(response);
            throw e;
        }
    }

    private String readRefreshToken(HttpServletRequest request) {
        return cookieUtil.getCookieValue(request, JWTConstants.CATEGORY_REFRESH)
                .orElseThrow(() -> invalidCredentials(SecurityCode.REFRESH_TOKEN_REQUIRE, "리프레시 토큰이 없습니다."));
    }

    private String reissueAccessToken(String refreshToken) {
        RefreshTokenClaims claims = validateRefreshToken(refreshToken);
        return createAccessToken(claims);
    }

    private RefreshTokenClaims validateRefreshToken(String refreshToken) {
        try {
            Claims claims = jwtUtil.getClaims(refreshToken);
            validateRefreshTokenCategory(claims);

            Long userId = claims.get(JWTConstants.CLAIM_USER_ID, Long.class);
            validateSavedRefreshToken(userId, refreshToken);

            String role = claims.get(JWTConstants.CLAIM_ROLE, String.class);
            return new RefreshTokenClaims(userId, role);
        } catch (ExpiredJwtException e) {
            throw invalidCredentials(SecurityCode.REFRESH_TOKEN_EXPIRED, "리프레시 토큰이 만료되었습니다.");
        } catch (JwtException | IllegalArgumentException e) {
            log.info("유효하지 않은 리프레시 토큰입니다: {}", e.getClass().getSimpleName());
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
        }
    }

    private void validateRefreshTokenCategory(Claims claims) {
        String category = claims.get(JWTConstants.CLAIM_CATEGORY, String.class);
        if (!JWTConstants.CATEGORY_REFRESH.equals(category)) {
            throw invalidCredentials(SecurityCode.INVALID_TOKEN_TYPE, "잘못된 토큰 카테고리 입니다.");
        }
    }

    private void validateSavedRefreshToken(Long userId, String refreshToken) {
        String savedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (!refreshToken.equals(savedToken)) {
            throw invalidCredentials(SecurityCode.INVALID_TOKEN, "사용자 정보와 일치하지 않는 토큰입니다.");
        }
    }

    private String createAccessToken(RefreshTokenClaims claims) {
        return jwtUtil.createJwt(claims.userId(), claims.role(), JWTConstants.CATEGORY_ACCESS);
    }

    private InvalidCredentialsException invalidCredentials(SecurityCode code, String message) {
        log.info(message);
        return new InvalidCredentialsException(code);
    }

    private record RefreshTokenClaims(Long userId, String role) {
    }

    public void logout(Long userId) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }

    @Transactional
    public void signUp(SignUpRequest request) {
        log.info("회원가입 시작");

        validateSignUpRequest(request);
        consumeSignUpVerifications(request);
        userRepository.save(createLocalUser(request));
        log.info("USER 저장됨");
    }

    private void validateSignUpRequest(SignUpRequest request) {
        validatePasswordConfirmation(request);
        validateUniqueEmail(request.getEmail());
        validateUniquePhoneNumber(request.getPhoneNumber());
        validateMembershipProfile(request);
        validateVerifiedEmail(request.getEmail());
    }

    private void validatePasswordConfirmation(SignUpRequest request) {
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            log.info("비밀번호가 일치하지 않음");
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }
    }

    private void validateUniqueEmail(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            log.info("이메일 중복");
            throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
        }
    }

    private void validateUniquePhoneNumber(String phoneNumber) {
        if (userRepository.findByPhoneNumber(phoneNumber).isPresent()) {
            log.info("휴대폰 번호 중복");
            throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
        }
    }

    private void validateMembershipProfile(SignUpRequest request) {
        if (!MembershipProfileValidator.isValid(request.getCarrier(), request.getMembershipGradeCode())) {
            throw new InvalidMembershipProfileException(UserCode.INVALID_MEMBERSHIP_PROFILE);
        }
    }

    private void validateVerifiedEmail(String email) {
        if (!emailService.hasVerified(email)) {
            log.info("이메일 인증 미완료");
            throw new EmailVerificationException(SecurityCode.EMAIL_VERIFICATION_FAILURE);
        }
    }

    private void consumeSignUpVerifications(SignUpRequest request) {
        if (!smsVerificationService.consumeVerified(request.getPhoneNumber())) {
            log.info("휴대폰 번호 인증 미완료");
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }

        if (!emailService.consumeVerified(request.getEmail())) {
            log.info("이메일 인증 소모 실패");
            throw new EmailVerificationException(SecurityCode.EMAIL_VERIFICATION_FAILURE);
        }
    }

    private User createLocalUser(SignUpRequest request) {
        User user = User.builder()
                .email(request.getEmail())
                .nickname(request.getNickname())
                .phoneNumber(request.getPhoneNumber())
                .gender(request.getGender())
                .carrier(request.getCarrier())
                .membershipGradeCode(request.getMembershipGradeCode())
                .membershipVerified(false)
                .birthday(request.getBirthday())
                .role(Role.USER)
                .build();

        user.getAuthCredentials().add(AuthCredential.localPassword(user, passwordEncoder.encode(request.getPassword())));
        return user;
    }
}
