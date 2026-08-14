package com.itplace.userapi.security.abuse;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientAddressResolver {

    private ClientAddressResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
    }
}
