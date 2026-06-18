package com.itplace.userapi.security.auth.oauth.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuthSignUpRequest {

    @NotBlank(message = "닉네임은 필수 항목입니다.")
    private String nickname;

    @NotNull(message = "성별은 필수 항목입니다.")
    private Gender gender;

    @NotNull(message = "생년월일은 필수 입력입니다.")
    @Past(message = "생년월일은 과거 날짜여야 합니다.")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthday;

    @NotNull(message = "통신사는 필수 항목입니다.")
    private Carrier carrier;

    @NotNull(message = "멤버십 등급은 필수 항목입니다.")
    private Grade membershipGradeCode;
}
