package com.itplace.userapi.security.auth.local.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.security.auth.local.service.AuthService;
import com.itplace.userapi.security.jwt.JWTConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;

    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {
        authService.reissue(request, response);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.RENEW_ACCESS_TOKEN);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {
        authService.logout(userDetails.getUser().getId());
        cookieUtil.expireCookie(response, JWTConstants.CATEGORY_ACCESS);
        cookieUtil.expireCookie(response, JWTConstants.CATEGORY_REFRESH);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.LOGOUT_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
