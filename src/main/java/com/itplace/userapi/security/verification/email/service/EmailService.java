package com.itplace.userapi.security.verification.email.service;

import com.itplace.userapi.security.verification.email.dto.request.EmailConfirmRequest;
import com.itplace.userapi.security.verification.email.dto.request.EmailVerificationRequest;

public interface EmailService {

    void send(EmailVerificationRequest request);

    void confirm(EmailConfirmRequest request);
}
