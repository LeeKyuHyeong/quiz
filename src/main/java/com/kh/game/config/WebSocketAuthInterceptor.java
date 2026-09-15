package com.kh.game.config;

import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GameRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP SUBSCRIBE 인가 인터셉터.
 *
 * 인증 자체는 여기서 하지 않는다. 핸드셰이크 요청의 Principal(Spring Security 세션 사용자)이
 * WebSocket 세션 사용자로 전파되어 모든 STOMP 프레임의 {@code accessor.getUser()}에 실려 온다.
 * 이 인터셉터는 그 사용자가 구독하려는 방({@code /topic/room/{roomCode}})의
 * 활성 참가자(JOINED/PLAYING)인지만 검사하고, 아니면 {@link AccessDeniedException}을 던진다.
 * 예외가 나면 Spring이 ERROR 프레임을 보내고 세션을 닫으며, 클라이언트(ws-client.js)는 재시도 후 폴링으로 넘어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    static final String ROOM_TOPIC_PREFIX = "/topic/room/";

    private final GameRoomService gameRoomService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        String roomCode = extractRoomCode(destination);
        if (roomCode == null) {
            throw deny(destination, accessor.getUser(), "허용되지 않은 구독 대상입니다.");
        }

        Long memberId = extractMemberId(accessor.getUser());
        if (memberId == null) {
            throw deny(destination, accessor.getUser(), "로그인이 필요합니다.");
        }

        if (!gameRoomService.isActiveParticipant(roomCode, memberId)) {
            throw deny(destination, accessor.getUser(), "방 참가자만 구독할 수 있습니다.");
        }

        return message;
    }

    /** {@code /topic/room/{roomCode}} 형식이면 roomCode, 아니면 null */
    private String extractRoomCode(String destination) {
        if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
            return null;
        }
        String roomCode = destination.substring(ROOM_TOPIC_PREFIX.length());
        if (roomCode.isEmpty() || roomCode.contains("/")) {
            return null;
        }
        return roomCode;
    }

    private Long extractMemberId(Principal user) {
        if (user instanceof Authentication auth && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getMember().getId();
        }
        return null;
    }

    private AccessDeniedException deny(String destination, Principal user, String reason) {
        log.warn("WS SUBSCRIBE denied: dest={} user={} reason={}",
                destination, user != null ? user.getName() : null, reason);
        return new AccessDeniedException(reason);
    }
}
