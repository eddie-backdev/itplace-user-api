package com.itplace.userapi.security.verification.sms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SmsVerificationIssueRequest {

    @NotBlank(message = "휴대폰 번호는 필수 항목입니다.")
    @Pattern(regexp = "^01\\d{8,9}$", message = "휴대폰 번호는 '-' 없이 입력해주세요.")
    private String phoneNumber;
}
