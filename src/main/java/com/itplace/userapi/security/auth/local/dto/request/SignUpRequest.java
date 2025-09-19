package com.itplace.userapi.security.auth.local.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itplace.userapi.user.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@ToString
public class SignUpRequest {
    @NotBlank(message = "이름은 필수 항목입니다.")
    private String name;

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "유효한 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "전화번호는 필수 항목입니다.")
    @Pattern(regexp = "^010[0-9]{8}$", message = "전화번호 형식이 올바르지 않습니다.")
    private String phoneNumber;

    @NotBlank(message = "비밀번호는 필수 항목입니다.")
    @Size(min = 6, max = 30, message = "비밀번호는 6자 이상 30자 이하로 입력해주세요.")
    @Pattern(
            regexp = "^(?=.*[!@#$%^&*()_+{}\\[\\]:;<>,.?~/-]).*$",
            message = "비밀번호에는 특수문자를 최소 1개 이상 포함해야 합니다."
    )
    private String password;

    @NotBlank(message = "비밀번호 확인은 필수 항목입니다.")
    @Size(min = 6, max = 30, message = "비밀번호는 6자 이상 30자 이하로 입력해주세요.")
    @Pattern(
            regexp = "^(?=.*[!@#$%^&*()_+{}\\[\\]:;<>,.?~/-]).*$",
            message = "비밀번호에는 특수문자를 최소 1개 이상 포함해야 합니다."
    )
    private String passwordConfirm;

    @NotNull(message = "성별은 필수 항목입니다.")
    private Gender gender;

    private String membershipId;

    @NotNull(message = "생년월일은 필수 입력입니다.")
    @Past(message = "생년월일은 과거 날짜여야 합니다.")
    @JsonFormat(pattern = "yyyy-MM-dd")  // Jackson이 "2025-07-12" 형식의 문자열을 LocalDate로 변환
    private LocalDate birthday;
}
