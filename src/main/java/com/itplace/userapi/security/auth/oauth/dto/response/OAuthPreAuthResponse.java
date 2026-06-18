package com.itplace.userapi.security.auth.oauth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OAuthPreAuthResponse {
    private final String email;
    private final String nickname;
}
