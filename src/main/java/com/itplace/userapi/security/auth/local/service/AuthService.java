package com.itplace.userapi.security.auth.local.service;

import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    void reissue(HttpServletRequest request, HttpServletResponse response);

    void logout(Long userId);

    void signUp(SignUpRequest request);
}
