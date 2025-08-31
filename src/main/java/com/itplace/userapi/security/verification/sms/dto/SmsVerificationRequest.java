package com.itplace.userapi.security.verification.sms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SmsVerificationRequest {

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @NotBlank(message = "휴대폰 번호를 입력해주세요.")
    @Pattern(regexp = "^010[0-9]{8}$", message = "휴대폰 번호는 '010'으로 시작하는 11자리 숫자여야 합니다.")
    private String phoneNumber;

}
