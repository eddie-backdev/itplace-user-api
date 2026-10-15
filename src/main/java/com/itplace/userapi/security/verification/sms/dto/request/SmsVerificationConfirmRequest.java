package com.itplace.userapi.security.verification.sms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SmsVerificationConfirmRequest {

    @NotBlank(message = "휴대폰 번호는 필수 항목입니다.")
    @Pattern(regexp = "^01\\d{8,9}$", message = "휴대폰 번호는 '-' 없이 입력해주세요.")
    private String phoneNumber;

    @NotBlank(message = "인증 문자열은 필수 항목입니다.")
    @Pattern(regexp = "^[0-9A-F]{32}$", message = "인증 문자열 형식이 올바르지 않습니다.")
    private String verificationText;
}
