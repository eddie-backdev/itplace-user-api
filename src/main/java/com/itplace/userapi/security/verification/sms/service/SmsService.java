package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.verification.sms.dto.request.SmsConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.response.SmsConfirmResponse;
import com.itplace.userapi.security.verification.sms.dto.request.SmsVerificationRequest;

public interface SmsService {

    void send(SmsVerificationRequest request);

    SmsConfirmResponse confirm(SmsConfirmRequest request);
}
