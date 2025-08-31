package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.SmsVerificationException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.verification.sms.dto.SmsConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.SmsConfirmResponse;
import com.itplace.userapi.security.verification.sms.dto.SmsVerificationRequest;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.entity.UserStatus;
import com.itplace.userapi.user.repository.UplusDataRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsServiceImpl implements SmsService {

    private final UserRepository userRepository;
    private final UplusDataRepository uplusDataRepository;
    private final CoolSmsService coolSmsService;
    private final OtpUtil otpUtil;

    @Override
    public void send(SmsVerificationRequest request) {
        log.info("SmsVerificationRequest: {}", request);

        String phoneNumber = request.getPhoneNumber();

        String key = "sms:" + phoneNumber;
        String code = otpUtil.generateSmsOtp(phoneNumber);

        log.info("sms key: {}", key);
        log.info("sms code: {}", code);

        coolSmsService.sendMessage(code, phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public SmsConfirmResponse confirm(SmsConfirmRequest request) {
        log.info("SmsConfirmRequest: {}", request);
        String phoneNumber = request.getPhoneNumber();
        String code = request.getVerificationCode();

        if (otpUtil.validateSmsOtp(phoneNumber, code)) {
            log.info("SMS 인증 성공");
            Optional<User> userOpt = userRepository.findByPhoneNumber(phoneNumber);
            UserStatus userStatus = userOpt.isPresent() ? UserStatus.EXISTING_USER : UserStatus.NEW_USER;
            boolean isLocalUser = userOpt.map(user -> user.getEmail() != null).orElse(false);
            boolean uplusData = uplusDataRepository.findByPhoneNumber(phoneNumber).isPresent();
            log.info("UserStatus: {}, isLocalUser: {}, UplusData: {}", userStatus, isLocalUser, uplusData);
            return SmsConfirmResponse.builder()
                    .userStatus(userStatus)
                    .localUser(isLocalUser)
                    .uplusDataExists(uplusData)
                    .build();
        } else {
            log.info("SMS 인증 실패");
            throw new SmsVerificationException(SecurityCode.SMS_CODE_FAILURE);
        }
    }
}
