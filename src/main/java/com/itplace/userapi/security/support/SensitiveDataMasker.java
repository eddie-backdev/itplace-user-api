package com.itplace.userapi.security.support;

public final class SensitiveDataMasker {

    private SensitiveDataMasker() {
    }

    public static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 7) {
            return "***";
        }
        return phoneNumber.substring(0, 3)
                + "*".repeat(phoneNumber.length() - 7)
                + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
