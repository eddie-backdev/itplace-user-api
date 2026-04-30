package com.itplace.userapi.chat.controller;

import com.itplace.userapi.chat.dto.request.ChatMessageRequest;
import com.itplace.userapi.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;

    @MessageMapping("/chat/{sessionUuid}/send")
    public void handleUserMessage(
            @DestinationVariable String sessionUuid,
            @Payload ChatMessageRequest request) {
        chatService.handleUserMessage(sessionUuid, request.content());
    }
}
