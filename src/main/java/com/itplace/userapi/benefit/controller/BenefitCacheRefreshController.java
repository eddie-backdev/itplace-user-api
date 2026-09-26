package com.itplace.userapi.benefit.controller;

import com.itplace.userapi.benefit.service.BenefitCacheRefreshService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

@RestController
@RequiredArgsConstructor
public class BenefitCacheRefreshController {
    private final BenefitCacheRefreshService refreshService;

    @Value("${app.internal.api-key:}")
    private String expectedApiKey;

    @PostMapping(value = {"/internal/benefits/cache/refresh", "/api/v1/internal/benefits/cache/refresh"},
            produces = "application/json")
    public Map<String, Integer> refresh(
            @RequestHeader(name = "X-Internal-Api-Key", required = false) String apiKey) {
        if (!StringUtils.hasText(expectedApiKey) || !StringUtils.hasText(apiKey)
                || !MessageDigest.isEqual(expectedApiKey.getBytes(StandardCharsets.UTF_8),
                        apiKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return Map.of("refreshedPartners", refreshService.refreshPending());
    }
}
