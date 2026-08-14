package com.itplace.userapi.security.verification.email.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class EmailConfirmRequest {

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "이메일 형식을 맞춰주세요.")
    private String email;

    @NotBlank(message = "인증번호는 필수 항목입니다.")
    @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자여야 합니다.")
    private String verificationCode;
}
