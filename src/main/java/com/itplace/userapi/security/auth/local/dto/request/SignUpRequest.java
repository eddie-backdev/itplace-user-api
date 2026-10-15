package com.itplace.userapi.security.auth.local.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.support.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignUpRequest {
    @NotBlank(message = "닉네임은 필수 항목입니다.")
    private String nickname;

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "유효한 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String password;

    @NotBlank(message = "비밀번호 확인은 필수 항목입니다.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.SPECIAL_CHARACTER_PATTERN, message = PasswordPolicy.SPECIAL_CHARACTER_MESSAGE)
    private String passwordConfirm;

    @NotNull(message = "성별은 필수 항목입니다.")
    private Gender gender;

    private Carrier carrier;

    private Grade membershipGradeCode;

    @NotNull(message = "생년월일은 필수 입력입니다.")
    @Past(message = "생년월일은 과거 날짜여야 합니다.")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthday;
}
