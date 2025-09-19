package com.itplace.userapi.user.service;

import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.verification.email.dto.EmailConfirmRequest;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.FindEmailConfirmRequest;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.dto.response.CheckUplusDataResponse;
import com.itplace.userapi.user.dto.response.FindEmailResponse;
import com.itplace.userapi.user.dto.response.FindPasswordConfirmResponse;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import com.itplace.userapi.user.entity.Membership;
import com.itplace.userapi.user.entity.UplusData;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UplusDataRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UplusDataRepository uplusDataRepository;
    private final MembershipRepository membershipRepository;
    private final StringRedisTemplate redisTemplate;
    private final OtpUtil otpUtil;
    private final PasswordEncoder passwordEncoder;
    private final FavoriteRepository favoriteRepository;
    private final SocialAccountRepository socialAccountRepository;

    private static final String RESET_PASSWORD_PREFIX = "resetPassword:";
    private static final String RESET_PASSWORD_VALUE = "true";

    @Override
    @Transactional(readOnly = true)
    public UserInfoResponse getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        Membership membership = null;
        if (user.getMembershipId() != null) {
            membership = membershipRepository.findById(user.getMembershipId())
                    .orElse(null);
        }

        return UserInfoResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .gender(user.getGender())
                .birthday(user.getBirthday())
                .membershipId(membership != null ? membership.getMembershipId() : null)
                .membershipGrade(membership != null ? membership.getGrade() : null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public FindEmailResponse findEmailConfirm(FindEmailConfirmRequest request) {
        if (otpUtil.validateSmsOtp(request.getPhoneNumber(), request.getVerificationCode())) {
            log.info("SMS 인증 성공");
            User user = userRepository.findByPhoneNumberAndName(request.getPhoneNumber(), request.getName())
                    .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
            return FindEmailResponse.builder()
                    .email(user.getEmail())
                    .build();
        } else {
            log.info("SMS 인증 실패");
            throw new SmsVerificationException(SecurityCode.SMS_VERIFICATION_FAILURE);
        }
    }

    @Override
    public FindPasswordConfirmResponse findPasswordConfirm(EmailConfirmRequest request) {
        if (otpUtil.validateEmailOtp(request.getEmail(), request.getVerificationCode())) {
            log.info("Email 인증 성공");
            String resetPasswordToken = UUID.randomUUID().toString();
            String key = RESET_PASSWORD_PREFIX + resetPasswordToken;
            redisTemplate.opsForValue().set(key, RESET_PASSWORD_VALUE, 5, TimeUnit.MINUTES);
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
        String resetPasswordToken = request.getResetPasswordToken();
        String value = redisTemplate.opsForValue().get(RESET_PASSWORD_PREFIX + resetPasswordToken);
        if (RESET_PASSWORD_VALUE.equals(value)) {
            if (request.getNewPassword().equals(request.getNewPasswordConfirm())) {
                User user = userRepository.findByEmail(request.getEmail())
                        .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
                user.setPassword(passwordEncoder.encode(request.getNewPassword()));
                userRepository.save(user);
            } else {
                throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
            }
        } else {
            throw new IllegalArgumentException("유효하지 않은 토큰입니다.");
        }
    }

    @Override
    @Transactional
    public void changePassword(PrincipalDetails principalDetails, ChangePasswordRequest request) {
        User user = userRepository.findById(principalDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        if (passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        } else {
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }
    }

    @Override
    @Transactional
    public void withdraw(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        // 비밀번호 일치 여부 확인
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new PasswordMismatchException(SecurityCode.PASSWORD_MISMATCH);
        }

        favoriteRepository.deleteByUser_Id(userId);
        socialAccountRepository.deleteByUser_Id(userId);
        userRepository.delete(user);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckUplusDataResponse checkUplusData(PrincipalDetails principalDetails) {
        User user = userRepository.findById(principalDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        String phoneNumber = user.getPhoneNumber();
        boolean uplusDataExists = uplusDataRepository.findByPhoneNumber(phoneNumber).isPresent();
        return CheckUplusDataResponse.builder()
                .uplusDataExists(uplusDataExists)
                .build();
    }

    @Override
    @Transactional
    public void linkUplusData(PrincipalDetails principalDetails) {
        User user = userRepository.findById(principalDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        String phoneNumber = user.getPhoneNumber();
        UplusData uplusData = uplusDataRepository.findByPhoneNumber(phoneNumber).get();
        user.setGender(uplusData.getGender());
        user.setBirthday(uplusData.getBirthday());
        user.setMembershipId(uplusData.getMembershipId());
    }

    @Override
    public Integer getUserCouponCount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));
        return user.getCoupon();
    }
}