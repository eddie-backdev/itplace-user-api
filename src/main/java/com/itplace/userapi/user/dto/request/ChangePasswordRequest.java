package com.itplace.userapi.user.dto.request;

import com.itplace.userapi.user.support.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank(message = "기존 비밀번호는 필수 항목입니다.")
    private String oldPassword;

    @NotBlank(message = "새 비밀번호는 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인은 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String newPasswordConfirm;

}
