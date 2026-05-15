package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationIssueRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsVerificationIssueResponse;

public interface SmsVerificationService {
    SmsVerificationIssueResponse issue(SmsVerificationIssueRequest request);

    void confirm(SmsVerificationConfirmRequest request);

    boolean consumeVerified(String phoneNumber);
}
