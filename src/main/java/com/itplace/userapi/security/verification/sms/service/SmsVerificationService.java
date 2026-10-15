package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;

public interface SmsVerificationService {
    SmsVerificationIssueResponse issue(Long userId, SmsVerificationIssueRequest request, String clientAddress);

    void confirm(Long userId, SmsVerificationConfirmRequest request, String clientAddress);
}
