package com.itplace.userapi.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class FindEmailConfirmRequest {

    @NotBlank(message = "이름은 필수 항목입니다.")
    private String name;

    @NotBlank(message = "전화번호는 필수 항목입니다.")
    @Pattern(regexp = "^010[0-9]{8}$", message = "전화번호 형식이 올바르지 않습니다.")
    private String phoneNumber;

    @NotBlank(message = "인증번호를 입력해주세요.")
    @Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 6자리 숫자입니다.")
    private String verificationCode;
}
