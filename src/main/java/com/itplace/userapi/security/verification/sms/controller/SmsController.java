package com.itplace.userapi.security.verification.sms.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.verification.sms.dto.SmsConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.SmsConfirmResponse;
import com.itplace.userapi.security.verification.sms.dto.SmsVerificationRequest;
import com.itplace.userapi.security.verification.sms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/verification")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @PostMapping("/sms")
    public ResponseEntity<ApiResponse<Void>> send(@RequestBody @Validated SmsVerificationRequest request) {
        smsService.send(request);
        ApiResponse<Void> body = ApiResponse.of(SecurityCode.SMS_SEND_SUCCESS, null);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @PostMapping("/sms/confirm")
    public ResponseEntity<ApiResponse<SmsConfirmResponse>> confirm(@RequestBody @Validated SmsConfirmRequest request) {
        SmsConfirmResponse data = smsService.confirm(request);
        ApiResponse<SmsConfirmResponse> body = ApiResponse.of(SecurityCode.SMS_VERIFICATION_SUCCESS, data);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
