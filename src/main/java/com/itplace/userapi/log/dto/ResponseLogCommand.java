package com.itplace.userapi.log.dto;

public record ResponseLogCommand(
        String event,
        Long benefitId,
        Long partnerId,
        String path,
        String param
) {
}
