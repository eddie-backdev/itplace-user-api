package com.itplace.userapi.user.dto.request;

import com.itplace.userapi.user.support.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest {

    @NotBlank(message = "비밀번호 재설정 토큰은 필수 항목입니다.")
    private String resetPasswordToken;

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "유효한 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "새 비밀번호는 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인은 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String newPasswordConfirm;
}
