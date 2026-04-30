package com.itplace.userapi.chat.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.chat.redis.ChatRedisEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatRedisPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String channel, ChatRedisEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(channel, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ChatRedisEvent 직렬화 실패", e);
        }
    }
}
