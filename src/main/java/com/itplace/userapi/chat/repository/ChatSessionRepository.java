package com.itplace.userapi.chat.repository;

import com.itplace.userapi.chat.entity.ChatSession;
import com.itplace.userapi.chat.entity.SessionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findBySessionUuid(String sessionUuid);
    List<ChatSession> findByStatusOrderByCreatedDateDesc(SessionStatus status);
}
