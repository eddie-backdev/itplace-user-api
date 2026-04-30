package com.itplace.userapi.chat.dto.response;

import java.time.LocalDateTime;

public record ChatRoomResponse(
        String guestId,
        String sessionUuid,
        String status,
        LocalDateTime createdAt
) {}
