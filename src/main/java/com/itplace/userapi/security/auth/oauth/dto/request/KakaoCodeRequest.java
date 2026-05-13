package com.itplace.userapi.security.auth.oauth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KakaoCodeRequest {

    @NotBlank(message = "카카오 인가 코드는 필수 항목입니다.")
    private String code;

    /**
     * Mobile deep-link redirects use a different URI than the web OAuth redirect.
     * Kakao requires token exchange to use the same redirect_uri that issued the code.
     */
    private String redirectUri;
}
