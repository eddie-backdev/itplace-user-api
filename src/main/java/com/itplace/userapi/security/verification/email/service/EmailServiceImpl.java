package com.itplace.userapi.security.verification.email.service;

import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.DuplicateEmailException;
import com.itplace.userapi.security.exception.EmailVerificationException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.security.verification.email.dto.EmailConfirmRequest;
import com.itplace.userapi.security.verification.email.dto.EmailVerificationRequest;
import com.itplace.userapi.user.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender javaMailSender;
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final OtpUtil otpUtil;

    private static final long KEY_TTL_SECONDS = 300;
    private static final long VERIFIED_TTL_SECONDS = 1800;

    @Override
    public void send(EmailVerificationRequest request) {
        log.info("EmailVerificationRequest: {}", request);
        String email = request.getEmail();

        MimeMessage mimeMessage = javaMailSender.createMimeMessage();

        String code = otpUtil.generateEmailOtp(request.getEmail());

        log.info("email code: {}", code);

        try {
            MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, false, "UTF-8");

            // 메일을 받을 수신자 설정
            mimeMessageHelper.setTo(email); // 파라미터로 받은 이메일 주소 사용
            // 메일의 제목 설정
            mimeMessageHelper.setSubject("[itPlace] 이메일 인증 번호 안내");

            // html 문법 적용한 메일의 내용
            String content = String.format("""
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>이메일 인증</title>
                    </head>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; margin: 0; padding: 0;">
                        <table width="100%%" border="0" cellspacing="0" cellpadding="0">
                            <tr>
                                <td align="center">
                                    <table width="600" border="0" cellspacing="0" cellpadding="0" style="border-collapse: collapse; border: 1px solid #ddd; margin-top: 20px;">
                                        <tr>
                                            <td align="center" style="background-color: #7638FA; padding: 20px 0;">
                                                <h1 style="color: #ffffff; margin: 0;">IT:PLACE</h1>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td style="padding: 40px 30px;">
                                                <h2 style="color: #333;">이메일 주소를 인증해주세요</h2>
                                                <p style="margin: 20px 0;">
                                                    안녕하세요, IT:PLACE에 가입해 주셔서 감사합니다.<br>
                                                    회원가입을 완료하려면 아래 인증 번호를 입력해주세요.
                                                </p>
                                                <div style="margin: 30px auto; padding: 20px; background-color: #f4f4f4; border-radius: 5px; text-align: center;">
                                                    <p style="font-size: 16px; margin: 0; color: #555;">인증 번호</p>
                                                    <p style="font-size: 32px; font-weight: bold; color: #7638FA; margin: 10px 0; letter-spacing: 5px;">
                                                        %s
                                                    </p>
                                                </div>
                    
                                                <p style="margin-top: 40px; font-size: 12px; color: #999;">
                                                    본인이 요청한 것이 아니라면 이 이메일을 무시해주세요.
                                                </p>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td align="center" style="background-color: #f4f4f4; padding: 20px 30px; font-size: 12px; color: #777;">
                                                &copy; 2025 itPlace. All Rights Reserved.
                                            </td>
                                        </tr>
                                    </table>
                                </td>
                            </tr>
                        </table>
                    </body>
                    </html>
                    """, code); // String.format을 사용하여 인증번호를 삽입

            // 메일의 내용 설정
            mimeMessageHelper.setText(content, true);

            javaMailSender.send(mimeMessage);

            log.info("메일 발송 성공!");
        } catch (Exception e) {
            log.error("메일 발송 실패!", e);
            redisTemplate.delete("email:" + email);
            throw new EmailVerificationException(SecurityCode.EMAIL_SEND_FAILURE);
        }
    }

    @Override
    public void confirm(EmailConfirmRequest request) {
        log.info("EmailConfirmRequest: {}", request);

        if (otpUtil.validateEmailOtp(request.getEmail(), request.getVerificationCode())) {
            log.info("이메일 인증 성공: {}", request.getEmail());
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new DuplicateEmailException(SecurityCode.DUPLICATE_EMAIL);
            }
        } else {
            log.info("이메일 인증 실패");
            throw new EmailVerificationException(SecurityCode.EMAIL_VERIFICATION_FAILURE);
        }
    }
}
