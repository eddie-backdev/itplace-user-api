package com.itplace.userapi.security.auth.oauth.dto.response;

import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OAuthResult {
    private final LoginResponse loginResponse;

    private final String accessToken;
    private final String refreshToken;
}
