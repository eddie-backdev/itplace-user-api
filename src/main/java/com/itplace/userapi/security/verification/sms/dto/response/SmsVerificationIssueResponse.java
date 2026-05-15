package com.itplace.userapi.security.verification.sms.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SmsVerificationIssueResponse {
    private String phoneNumber;
    private String verificationText;
    private String receiverPhoneNumber;
    private long expiresInSeconds;
}
