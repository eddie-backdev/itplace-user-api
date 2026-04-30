package com.itplace.userapi.chat.service;

import com.itplace.userapi.chat.dto.response.ChatMessageResponse;
import com.itplace.userapi.chat.dto.response.ChatRoomResponse;
import com.itplace.userapi.chat.dto.response.ChatSessionResponse;
import com.itplace.userapi.chat.entity.ChatMessage;
import com.itplace.userapi.chat.entity.ChatSession;
import com.itplace.userapi.chat.entity.SenderType;
import com.itplace.userapi.chat.entity.SessionStatus;
import com.itplace.userapi.chat.exception.ChatSessionNotFoundException;
import com.itplace.userapi.chat.publisher.ChatRedisPublisher;
import com.itplace.userapi.chat.redis.ChatRedisEvent;
import com.itplace.userapi.chat.repository.ChatMessageRepository;
import com.itplace.userapi.chat.repository.ChatSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final String CHANNEL_SESSION_OPENED = "chat:event:session-opened";
    private static final String CHANNEL_MESSAGE = "chat:event:message";
    private static final String CHANNEL_SESSION_CLOSED = "chat:event:session-closed";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRedisPublisher chatRedisPublisher;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional
    public ChatSessionResponse createSession() {
        ChatSession session = chatSessionRepository.save(
                ChatSession.builder()
                        .sessionUuid(UUID.randomUUID().toString())
                        .build()
        );
        chatRedisPublisher.publish(CHANNEL_SESSION_OPENED, new ChatRedisEvent(
                "SESSION_OPENED", session.getSessionUuid(), session.getId(),
                null, null, LocalDateTime.now()
        ));
        return new ChatSessionResponse(session.getSessionUuid(), session.getCreatedDate());
    }

    @Override
    @Transactional
    public ChatRoomResponse createGuestRoom() {
        String uuid = UUID.randomUUID().toString();
        ChatSession session = chatSessionRepository.save(
                ChatSession.builder().sessionUuid(uuid).build()
        );
        chatRedisPublisher.publish(CHANNEL_SESSION_OPENED, new ChatRedisEvent(
                "SESSION_OPENED", uuid, session.getId(), null, null, LocalDateTime.now()
        ));
        return toRoomResponse(session);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRoomResponse getGuestRoom(String guestId) {
        String uuid = guestId.startsWith("guest-") ? guestId.substring(6) : guestId;
        ChatSession session = findSession(uuid);
        return toRoomResponse(session);
    }

    @Override
    @Transactional
    public void handleUserMessage(String sessionUuid, String content) {
        ChatSession session = findSession(sessionUuid);
        ChatMessage message = chatMessageRepository.save(
                ChatMessage.builder()
                        .session(session)
                        .senderType(SenderType.USER)
                        .content(content)
                        .build()
        );
        ChatMessageResponse response = toResponse(message);
        messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid, response);
        chatRedisPublisher.publish(CHANNEL_MESSAGE, new ChatRedisEvent(
                "NEW_MESSAGE", sessionUuid, session.getId(),
                "USER", content, LocalDateTime.now()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(String sessionUuid) {
        ChatSession session = findSession(sessionUuid);
        return chatMessageRepository.findBySessionOrderByCreatedDateAsc(session)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void closeSession(String sessionUuid) {
        ChatSession session = findSession(sessionUuid);
        if (session.getStatus() == SessionStatus.CLOSED) {
            return;
        }
        session.close();
        chatRedisPublisher.publish(CHANNEL_SESSION_CLOSED, new ChatRedisEvent(
                "SESSION_CLOSED", sessionUuid, session.getId(),
                null, null, LocalDateTime.now()
        ));
    }

    private ChatSession findSession(String sessionUuid) {
        return chatSessionRepository.findBySessionUuid(sessionUuid)
                .orElseThrow(ChatSessionNotFoundException::new);
    }

    private ChatRoomResponse toRoomResponse(ChatSession session) {
        return new ChatRoomResponse(
                "guest-" + session.getSessionUuid(),
                session.getSessionUuid(),
                session.getStatus().name(),
                session.getCreatedDate()
        );
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSenderType().name(),
                message.getContent(),
                message.getCreatedDate()
        );
    }
}
