package com.itplace.userapi.chat.repository;

import com.itplace.userapi.chat.entity.ChatMessage;
import com.itplace.userapi.chat.entity.ChatSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionOrderByCreatedDateAsc(ChatSession session);
}
