package com.itplace.userapi.security.verification.email.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.verification.email.dto.EmailConfirmRequest;
import com.itplace.userapi.security.verification.email.dto.EmailVerificationRequest;
import com.itplace.userapi.security.verification.email.service.EmailService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증, 인가, 로그인, 회원가입 관련 API")
@RestController
@RequestMapping("/api/v1/verification")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    @PostMapping("/email")
    public ResponseEntity<ApiResponse<Void>> send(@RequestBody @Validated EmailVerificationRequest request) {
        emailService.send(request);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.EMAIL_SEND_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/email/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(@RequestBody @Validated EmailConfirmRequest request) {
        emailService.confirm(request);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.EMAIL_VERIFICATION_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
