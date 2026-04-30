package com.itplace.userapi.chat.service;

import com.itplace.userapi.chat.dto.response.ChatMessageResponse;
import com.itplace.userapi.chat.dto.response.ChatRoomResponse;
import com.itplace.userapi.chat.dto.response.ChatSessionResponse;
import java.util.List;

public interface ChatService {
    ChatSessionResponse createSession();
    ChatRoomResponse createGuestRoom();
    ChatRoomResponse getGuestRoom(String guestId);
    void handleUserMessage(String sessionUuid, String content);
    List<ChatMessageResponse> getMessages(String sessionUuid);
    void closeSession(String sessionUuid);
}
