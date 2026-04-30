package com.itplace.userapi.chat.controller;

import com.itplace.userapi.chat.ChatCode;
import com.itplace.userapi.chat.dto.response.ChatMessageResponse;
import com.itplace.userapi.chat.dto.response.ChatRoomResponse;
import com.itplace.userapi.chat.dto.response.ChatSessionResponse;
import com.itplace.userapi.chat.service.ChatService;
import com.itplace.userapi.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chat", description = "실시간 채팅 상담 API (REST + WebSocket/STOMP)")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatService chatService;

    @Operation(
            summary = "게스트 채팅방 생성",
            description = """
                    비로그인 사용자가 채팅 상담을 시작합니다.

                    응답으로 받은 `sessionUuid`를 WebSocket 연결 및 STOMP 구독에 사용합니다.
                    - WebSocket 연결: `ws://host/ws-chat` (SockJS)
                    - 메시지 구독: `/topic/chat/{sessionUuid}`
                    - 메시지 전송: `/app/chat/{sessionUuid}/send`
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="201", description = "채팅방 생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="500", description = "서버 오류")
    })
    @PostMapping("/rooms/guest")
    public ResponseEntity<com.itplace.userapi.common.ApiResponse<ChatRoomResponse>> createGuestRoom() {
        ChatRoomResponse response = chatService.createGuestRoom();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(com.itplace.userapi.common.ApiResponse.of(ChatCode.SESSION_CREATED, response));
    }

    @Operation(summary = "게스트 채팅방 조회", description = "guestId로 기존 채팅방 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "채팅방 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "채팅방을 찾을 수 없음")
    })
    @GetMapping("/rooms/guest/{guestId}")
    public ResponseEntity<com.itplace.userapi.common.ApiResponse<ChatRoomResponse>> getGuestRoom(
            @Parameter(description = "채팅방 생성 시 발급된 게스트 ID (guest-{uuid})", example = "guest-550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String guestId) {
        ChatRoomResponse response = chatService.getGuestRoom(guestId);
        return ResponseEntity.ok(com.itplace.userapi.common.ApiResponse.of(ChatCode.MESSAGES_FETCHED, response));
    }

    @Operation(summary = "채팅 메시지 이력 조회", description = "특정 세션의 전체 메시지 이력을 시간 순으로 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "메시지 이력 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "세션을 찾을 수 없음")
    })
    @GetMapping("/sessions/{sessionUuid}/messages")
    public ResponseEntity<com.itplace.userapi.common.ApiResponse<List<ChatMessageResponse>>> getMessages(
            @Parameter(description = "채팅 세션 UUID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String sessionUuid) {
        List<ChatMessageResponse> messages = chatService.getMessages(sessionUuid);
        return ResponseEntity.ok(com.itplace.userapi.common.ApiResponse.of(ChatCode.MESSAGES_FETCHED, messages));
    }

    @Operation(summary = "채팅 세션 생성 (기본)", description = "sessionUuid를 발급합니다. /rooms/guest 사용을 권장합니다.")
    @PostMapping("/sessions")
    public ResponseEntity<com.itplace.userapi.common.ApiResponse<ChatSessionResponse>> createSession() {
        ChatSessionResponse response = chatService.createSession();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(com.itplace.userapi.common.ApiResponse.of(ChatCode.SESSION_CREATED, response));
    }
}
