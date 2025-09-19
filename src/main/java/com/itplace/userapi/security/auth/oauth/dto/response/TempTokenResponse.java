package com.itplace.userapi.security.auth.oauth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TempTokenResponse {
    private String tempToken;
}
