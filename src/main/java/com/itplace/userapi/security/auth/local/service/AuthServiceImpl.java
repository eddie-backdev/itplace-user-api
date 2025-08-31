package com.itplace.userapi.security.auth.local.service;

import static com.itplace.userapi.security.auth.local.filter.LoginFilter.REFRESH_TOKEN_PREFIX;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.request.LinkLocalRequest;
import com.itplace.userapi.security.auth.local.dto.request.LoadOAuthDataRequest;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.auth.local.dto.request.UplusDataRequest;
import com.itplace.userapi.security.auth.local.dto.response.LoadOAuthDataResponse;
import com.itplace.userapi.security.auth.local.dto.response.UplusDataResponse;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.DuplicatePhoneNumberException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.security.jwt.JWTConstants;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UplusDataRepository;
import com.itplace.userapi.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final UplusDataRepository uplusDataRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JWTUtil jwtUtil;

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

        String newAccessToken = validateRefreshTokenAndGetNewAccessToken(refreshToken);

        // 새 Access Token을 쿠키에 담아 응답
        ResponseCookie accessTokenCookie = ResponseCookie.from(JWTConstants.CATEGORY_ACCESS, newAccessToken)
                .path("/")
                .secure(true) // 로컬이 아닐때만 secure
                .sameSite("None")
                .httpOnly(true)
                .domain("itplace.click")
                .maxAge(jwtUtil.getAccessTokenValidityInMS() / 1000)
                .build();
        response.addHeader("Set-Cookie", accessTokenCookie.toString());
    }

    private String validateRefreshTokenAndGetNewAccessToken(String refreshToken) {
        // Refresh Token 부재 시 예외 발생
        if (refreshToken == null) {
            log.info("리프레시 토큰이 없습니다.");
            throw new InvalidCredentialsException(SecurityCode.REFRESH_TOKEN_REQUIRE);
        }

        // Refresh Token 만료 여부 확인
        if (jwtUtil.isExpired(refreshToken)) {
            log.info("리프레시 토큰이 만료되었습니다.");
            throw new InvalidCredentialsException(SecurityCode.REFRESH_TOKEN_EXPIRED);
        }

        // 토큰 카테고리 확인
        String category = jwtUtil.getCategory(refreshToken);
        if (!category.equals(JWTConstants.CATEGORY_REFRESH)) {
            log.info("잘못된 토큰 카테고리 입니다.");
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN_TYPE);
        }

        // Redis에 저장된 토큰과 일치하는지 확인
        Long userId = jwtUtil.getUserId(refreshToken);
        String savedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (savedToken == null || !savedToken.equals(refreshToken)) {
            log.info("사용자 정보와 일치하지 않는 토큰입니다.");
            throw new InvalidCredentialsException(SecurityCode.INVALID_TOKEN);
        }

        // 새 Access Token 생성
        String role = jwtUtil.getRole(refreshToken).getKey();
        String newAccessToken = jwtUtil.createJwt(userId, role, JWTConstants.CATEGORY_ACCESS);
        return newAccessToken;
    }

    @Override
    public void logout(Long userId) {
        redisTemplate.delete("RT:" + userId);
    }

    @Override
    public Optional<UplusDataResponse> uplusData(UplusDataRequest request) {
        return uplusDataRepository.findByPhoneNumber(request.getPhoneNumber()).map(UplusDataResponse::from);
    }

    @Override
    @Transactional
    public void signUp(SignUpRequest request) {
        log.info("회원가입 시작 request: {}", request);

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            log.info("비밀번호가 일치하지 않음");
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        String membershipId = request.getMembershipId();
        if (membershipId != null && membershipId.isEmpty()) {
            membershipId = null;
        }

        User user = User.builder()
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .name(request.getName())
                .password(passwordEncoder.encode(request.getPassword()))
                .gender(request.getGender())
                .membershipId(membershipId)
                .birthday(request.getBirthday())
                .role(Role.USER)
                .build();

        log.info("User 정보: {}", user);

        userRepository.findByEmailOrPhoneNumber(request.getEmail(), request.getPhoneNumber())
                .ifPresent(existUser -> {
                    if (existUser.getEmail().equals(request.getEmail())) {
                        log.info("이메일 중복");
                        throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
                    } else if (existUser.getPhoneNumber().equals(request.getPhoneNumber())) {
                        log.info("전화번호 중복");
                        throw new DuplicatePhoneNumberException(SecurityCode.DUPLICATE_PHONE_NUMBER);
                    }
                });
        userRepository.save(user);

        log.info("USER 저장됨");
    }

    @Override
    @Transactional
    public LoadOAuthDataResponse loadOAuthData(LoadOAuthDataRequest request) {
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        return LoadOAuthDataResponse.builder()
                .name(user.getName())
                .phoneNumber(user.getPhoneNumber())
                .gender(user.getGender())
                .birthday(user.getBirthday())
                .membershipId(user.getMembershipId())
                .build();
    }

    @Override
    @Transactional
    public void link(LinkLocalRequest request) {
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            log.info("비밀번호가 일치하지 않음");
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
        }

        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
    }
}
