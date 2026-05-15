package com.itplace.userapi.security.verification.sms.service;

public interface OctomoMessageClient {
    boolean exists(String mobileNumber, String text);
}
