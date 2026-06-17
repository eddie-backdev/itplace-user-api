package com.itplace.userapi.user.service;

import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.verification.email.dto.request.EmailConfirmRequest;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.MembershipProfileUpdateRequest;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.dto.response.FindPasswordConfirmResponse;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.InvalidMembershipProfileException;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final OtpUtil otpUtil;
    private final PasswordEncoder passwordEncoder;
    private final FavoriteRepository favoriteRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final AuthCredentialRepository authCredentialRepository;

    private static final String RESET_PASSWORD_PREFIX = "resetPassword:";

    @Override
    @Transactional(readOnly = true)
    public UserInfoResponse getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        return UserInfoResponse.from(user);
    }


    @Override
    @Transactional
    public UserInfoResponse updateMembershipProfile(PrincipalDetails principalDetails, MembershipProfileUpdateRequest request) {
        if (!MembershipProfileValidator.isValid(request.getCarrier(), request.getMembershipGradeCode())) {
            throw new InvalidMembershipProfileException(UserCode.INVALID_MEMBERSHIP_PROFILE);
        }
        User user = userRepository.findById(requireUserId(principalDetails))
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        user.setCarrier(request.getCarrier());
        user.setMembershipGradeCode(request.getMembershipGradeCode());
        user.setMembershipVerified(false);
        return UserInfoResponse.from(user);
    }

    @Override
    public FindPasswordConfirmResponse findPasswordConfirm(EmailConfirmRequest request) {
        if (otpUtil.validateEmailOtp(request.getEmail(), request.getVerificationCode())) {
            log.info("Email 인증 성공");
            String resetPasswordToken = UUID.randomUUID().toString();
            String key = RESET_PASSWORD_PREFIX + resetPasswordToken;
            redisTemplate.opsForValue().set(key, request.getEmail(), 5, TimeUnit.MINUTES);
            return FindPasswordConfirmResponse.builder()
                    .resetPasswordToken(resetPasswordToken)
                    .build();
        } else {
            log.info("Email 인증 실패");
            throw new EmailVerificationException(SecurityCode.EMAIL_VERIFICATION_FAILURE);
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String key = RESET_PASSWORD_PREFIX + request.getResetPasswordToken();
        String storedEmail = redisTemplate.opsForValue().get(key);

        if (storedEmail == null || !storedEmail.equals(request.getEmail())) {
            throw new InvalidCredentialsException(SecurityCode.RESET_PASSWORD_FAILURE);
        }
        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        redisTemplate.delete(key);
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        upsertLocalPasswordCredential(user, passwordEncoder.encode(request.getNewPassword()));
    }

    @Override
    @Transactional
    public void changePassword(PrincipalDetails principalDetails, ChangePasswordRequest request) {
        User user = userRepository.findById(requireUserId(principalDetails))
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        AuthCredential localCredential = authCredentialRepository.findByUser_IdAndType(user.getId(), AuthCredentialType.LOCAL_PASSWORD)
                .orElseThrow(() -> new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH));

        if (!passwordEncoder.matches(request.getOldPassword(), localCredential.getPasswordHash())) {
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        localCredential.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
    }

    @Override
    @Transactional
    public void withdraw(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        authCredentialRepository.findByUser_IdAndType(userId, AuthCredentialType.LOCAL_PASSWORD)
                .ifPresent(localCredential -> {
                    if (!StringUtils.hasText(password) || !passwordEncoder.matches(password, localCredential.getPasswordHash())) {
                        throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
                    }
                });

        favoriteRepository.deleteByUser_Id(userId);
        authCredentialRepository.deleteByUser_Id(userId);
        socialAccountRepository.deleteByUser_Id(userId);
        userRepository.delete(user);
    }

    private void upsertLocalPasswordCredential(User user, String passwordHash) {
        authCredentialRepository.findByUser_IdAndType(user.getId(), AuthCredentialType.LOCAL_PASSWORD)
                .ifPresentOrElse(
                        credential -> credential.setPasswordHash(passwordHash),
                        () -> user.getAuthCredentials().add(AuthCredential.localPassword(user, passwordHash))
                );
    }

    private Long requireUserId(PrincipalDetails principalDetails) {
        if (principalDetails == null || principalDetails.getUserId() == null) {
            throw new UserNotFoundException(SecurityCode.USER_NOT_FOUND);
        }
        return principalDetails.getUserId();
    }

}
