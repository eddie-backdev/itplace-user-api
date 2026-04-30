package com.itplace.userapi.chat.dto.response;

import java.time.LocalDateTime;

public record ChatSessionResponse(String sessionUuid, LocalDateTime createdAt) {}
