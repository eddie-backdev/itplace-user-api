package com.itplace.userapi.log.interceptor;

import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoggingInterceptor implements HandlerInterceptor {

    private final LogService logService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();

        Long userId = extractUserId();
        String event = "click";
        Long benefitId = extractBenefitIdFromUri(uri);
        String path = uri;
        String params = request.getQueryString();

        if (userId != null && benefitId != null) {
            logService.saveRequestLog(userId, event, benefitId, path, params);
        }

        return true;
    }

    private Long extractBenefitIdFromUri(String uri) {
        String[] parts = uri.split("/");
        if (parts.length < 2) {
            return null;
        }
        String path = parts[parts.length - 2];
        String benefitId = parts[parts.length - 1];

        if (path != null && path.equals("benefit")) {
            try {
                return Long.parseLong(benefitId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Long extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            log.info("Authentication 정보 없음");
            return null;
        }
        Object principal = auth.getPrincipal();

        if (principal instanceof PrincipalDetails principalDetails) {
            return principalDetails.getUserId();
        }
        log.info("user 정보 없음");
        return null;
    }
}
