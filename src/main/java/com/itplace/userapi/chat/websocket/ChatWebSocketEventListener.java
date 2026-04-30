package com.itplace.userapi.chat.websocket;

import com.itplace.userapi.chat.service.ChatService;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Component
@RequiredArgsConstructor
public class ChatWebSocketEventListener {

    private final ChatService chatService;
    private final ConcurrentHashMap<String, String> stompSessionMap = new ConcurrentHashMap<>();

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String stompSessionId = accessor.getSessionId();
        if (destination != null && destination.startsWith("/topic/chat/") && stompSessionId != null) {
            String sessionUuid = destination.substring("/topic/chat/".length());
            stompSessionMap.put(stompSessionId, sessionUuid);
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String stompSessionId = accessor.getSessionId();
        String chatSessionUuid = stompSessionMap.remove(stompSessionId);
        if (chatSessionUuid != null) {
            chatService.closeSession(chatSessionUuid);
        }
    }
}
