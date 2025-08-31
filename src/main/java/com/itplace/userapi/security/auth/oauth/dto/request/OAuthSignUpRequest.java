package com.itplace.userapi.security.auth.oauth.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itplace.userapi.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuthSignUpRequest {

    @NotBlank(message = "이름은 필수 항목입니다.")
    private String name;

    @NotNull(message = "성별은 필수 항목입니다.")
    private Gender gender;

    @NotNull(message = "생년월일은 필수 입력입니다.")
    @Past(message = "생년월일은 과거 날짜여야 합니다.")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthday;

    @NotBlank(message = "전화번호는 필수 항목입니다.")
    @Pattern(regexp = "^010[0-9]{8}$", message = "전화번호 형식이 올바르지 않습니다.")
    private String phoneNumber;

    private String membershipId;
}