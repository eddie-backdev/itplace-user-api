package com.itplace.userapi.chat.dto.response;

import java.time.LocalDateTime;

public record ChatMessageResponse(Long id, String senderType, String content, LocalDateTime createdAt) {}
