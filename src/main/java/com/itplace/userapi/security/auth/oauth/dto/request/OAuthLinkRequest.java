package com.itplace.userapi.security.auth.oauth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuthLinkRequest {
    @NotBlank(message = "전화번호는 필수 항목입니다.")
    @Pattern(regexp = "^010[0-9]{8}$", message = "전화번호 형식이 올바르지 않습니다.")
    private String phoneNumber;
}
