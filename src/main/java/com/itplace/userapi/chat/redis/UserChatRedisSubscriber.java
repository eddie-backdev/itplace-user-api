package com.itplace.userapi.chat.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.chat.dto.response.ChatMessageResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserChatRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatRedisEvent event = objectMapper.readValue(json, ChatRedisEvent.class);
            ChatMessageResponse response = new ChatMessageResponse(
                    null, "ADMIN", event.content(), event.timestamp()
            );
            messagingTemplate.convertAndSend("/topic/chat/" + event.sessionUuid(), response);
        } catch (Exception e) {
            log.error("관리자 메시지 Redis 수신 처리 실패", e);
        }
    }
}
