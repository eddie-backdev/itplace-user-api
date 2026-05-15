package com.itplace.userapi.security.verification.sms.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;
import com.itplace.userapi.security.verification.sms.service.SmsVerificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Verification", description = "문자 인증 관련 API")
@RestController
@RequestMapping("/api/v1/verification/sms")
@RequiredArgsConstructor
public class SmsVerificationController {

    private final SmsVerificationService smsVerificationService;

    @PostMapping
    public ResponseEntity<ApiResponse<SmsVerificationIssueResponse>> issue(
            @RequestBody @Validated SmsVerificationIssueRequest request
    ) {
        SmsVerificationIssueResponse response = smsVerificationService.issue(request);
        ApiResponse<SmsVerificationIssueResponse> body = ApiResponse.of(SecurityCode.SMS_ISSUE_SUCCESS, response);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(@RequestBody @Validated SmsVerificationConfirmRequest request) {
        smsVerificationService.confirm(request);
        ApiResponse<Void> body = ApiResponse.ok(SecurityCode.SMS_VERIFICATION_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
