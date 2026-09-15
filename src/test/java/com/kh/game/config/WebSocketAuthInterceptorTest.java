package com.kh.game.config;

import com.kh.game.entity.Member;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GameRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketAuthInterceptor - STOMP SUBSCRIBE 방 참가자 인가")
class WebSocketAuthInterceptorTest {

    @Mock
    private GameRoomService gameRoomService;

    @Mock
    private MessageChannel channel;

    private WebSocketAuthInterceptor interceptor;
    private Authentication memberAuth;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(gameRoomService);

        Member member = new Member();
        member.setId(42L);
        member.setEmail("user@test.com");
        member.setNickname("tester");
        member.setRole(Member.MemberRole.USER);
        member.setStatus(Member.MemberStatus.ACTIVE);
        CustomUserDetails userDetails = new CustomUserDetails(member);
        memberAuth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    private Message<byte[]> stomp(StompCommand command, String destination, Authentication user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("활성 참가자의 방 토픽 구독은 통과한다")
    void subscribe_activeParticipant_passes() {
        when(gameRoomService.isActiveParticipant("ABC123", 42L)).thenReturn(true);
        Message<byte[]> message = stomp(StompCommand.SUBSCRIBE, "/topic/room/ABC123", memberAuth);

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("비로그인 구독은 거부한다 (참가자 조회도 하지 않음)")
    void subscribe_anonymous_denied() {
        Message<byte[]> message = stomp(StompCommand.SUBSCRIBE, "/topic/room/ABC123", null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("로그인이 필요합니다.");
        verify(gameRoomService, never()).isActiveParticipant(anyString(), anyLong());
    }

    @Test
    @DisplayName("비참가자(또는 LEFT) 구독은 거부한다")
    void subscribe_nonParticipant_denied() {
        when(gameRoomService.isActiveParticipant("ABC123", 42L)).thenReturn(false);
        Message<byte[]> message = stomp(StompCommand.SUBSCRIBE, "/topic/room/ABC123", memberAuth);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("방 참가자만 구독할 수 있습니다.");
    }

    @Test
    @DisplayName("/topic/room/{code} 형식이 아닌 목적지는 거부한다")
    void subscribe_unknownDestination_denied() {
        for (String dest : new String[]{"/topic/other", "/topic/room/", "/topic/room/ABC/extra", null}) {
            Message<byte[]> message = stomp(StompCommand.SUBSCRIBE, dest, memberAuth);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .as("destination=%s", dest)
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("허용되지 않은 구독 대상입니다.");
        }
        verify(gameRoomService, never()).isActiveParticipant(anyString(), anyLong());
    }

    @Test
    @DisplayName("SUBSCRIBE 외 명령(CONNECT/SEND/DISCONNECT)은 검사 없이 통과한다")
    void otherCommands_passThrough() {
        for (StompCommand command : new StompCommand[]{StompCommand.CONNECT, StompCommand.SEND, StompCommand.DISCONNECT}) {
            Message<byte[]> message = stomp(command, "/app/anything", null);

            Message<?> result = interceptor.preSend(message, channel);

            assertThat(result).as("command=%s", command).isSameAs(message);
        }
        verify(gameRoomService, never()).isActiveParticipant(anyString(), anyLong());
    }
}
