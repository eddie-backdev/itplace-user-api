package com.itplace.userapi.chat.redis;

import java.time.LocalDateTime;

public record ChatRedisEvent(
        String eventType,
        String sessionUuid,
        Long sessionId,
        String senderType,
        String content,
        LocalDateTime timestamp
) {}
