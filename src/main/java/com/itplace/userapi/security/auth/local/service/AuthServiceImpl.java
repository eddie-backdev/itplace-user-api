package com.itplace.userapi.security.auth.local.service;

import static com.itplace.userapi.security.auth.local.filter.LoginFilter.REFRESH_TOKEN_PREFIX;

import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.exception.InvalidMembershipProfileException;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
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
public class AuthServiceImpl implements AuthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JWTUtil jwtUtil;
    private final CookieUtil cookieUtil;

    @Override
    public void reissue(HttpServletRequest request, HttpServletResponse response) {
        log.info("Access 토큰 재발급 시작");
        String refreshToken = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(JWTConstants.CATEGORY_REFRESH)) {
                    refreshToken = cookie.getValue();
                    break;
                }
            }
        }

        try {
            String newAccessToken = validateRefreshTokenAndGetNewAccessToken(refreshToken);
            cookieUtil.setAccessTokenCookie(response, newAccessToken);
        } catch (InvalidCredentialsException e) {
            cookieUtil.expireCookie(response, JWTConstants.CATEGORY_ACCESS);
            cookieUtil.expireCookie(response, JWTConstants.CATEGORY_REFRESH);
            throw e;
        }
    }

    private String validateRefreshTokenAndGetNewAccessToken(String refreshToken) {
        if (refreshToken == null) {
            log.info("리프레시 토큰이 없습니다.");
            throw new InvalidCredentialsException(SecurityCode.REFRESH_TOKEN_REQUIRE);
        }

        try {
            if (jwtUtil.isExpired(refreshToken)) {
                log.info("리프레시 토큰이 만료되었습니다.");
                throw new InvalidCredentialsException(SecurityCode.REFRESH_TOKEN_EXPIRED);
            }

            String category = jwtUtil.getCategory(refreshToken);
            if (!category.equals(JWTConstants.CATEGORY_REFRESH)) {
                log.info("잘못된 토큰 카테고리 입니다.");
                throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN_TYPE);
            }

            Long userId = jwtUtil.getUserId(refreshToken);
            String savedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
            if (savedToken == null || !savedToken.equals(refreshToken)) {
                log.info("사용자 정보와 일치하지 않는 토큰입니다.");
                throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
            }

            String role = jwtUtil.getRole(refreshToken).getKey();
            return jwtUtil.createJwt(userId, role, JWTConstants.CATEGORY_ACCESS);
        } catch (ExpiredJwtException e) {
            log.info("리프레시 토큰이 만료되었습니다.");
            throw new InvalidCredentialsException(SecurityCode.REFRESH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.info("유효하지 않은 리프레시 토큰입니다: {}", e.getClass().getSimpleName());
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
        }
    }

    @Override
    public void logout(Long userId) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }

    @Override
    @Transactional
    public void signUp(SignUpRequest request) {
        log.info("회원가입 시작");

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            log.info("비밀번호가 일치하지 않음");
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            log.info("이메일 중복");
            throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
        }

        if (!MembershipProfileValidator.isValid(request.getCarrier(), request.getMembershipGradeCode())) {
            throw new InvalidMembershipProfileException(UserCode.INVALID_MEMBERSHIP_PROFILE);
        }

        User user = User.builder()
                .email(request.getEmail())
                .name(request.getName())
                .password(passwordEncoder.encode(request.getPassword()))
                .gender(request.getGender())
                .carrier(request.getCarrier())
                .membershipGradeCode(request.getMembershipGradeCode())
                .membershipVerified(false)
                .birthday(request.getBirthday())
                .role(Role.USER)
                .build();

        userRepository.save(user);
        log.info("USER 저장됨");
    }
}
