package com.itplace.userapi.security.verification.sms.service;

import com.itplace.userapi.security.verification.sms.dto.SmsConfirmRequest;
import com.itplace.userapi.security.verification.sms.dto.SmsConfirmResponse;
import com.itplace.userapi.security.verification.sms.dto.SmsVerificationRequest;

public interface SmsService {

    void send(SmsVerificationRequest request);

    SmsConfirmResponse confirm(SmsConfirmRequest request);
}
