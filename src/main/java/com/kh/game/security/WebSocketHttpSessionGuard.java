package com.kh.game.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 세션이 끝난 WebSocket 연결을 닫는다 (세션 수명 계약: "만료되면 그 세션에 묶인 WebSocket 도 닫힌다").
 *
 * <p>메모리 세션에서는 Tomcat 이 한다 — HttpSession 이 소멸할 때 그 세션에서 연 WebSocket 을 닫는다(WsSessionListener).
 * DB 세션 저장소({@code session-jdbc})에서는 Tomcat 세션을 쓰지 않으므로 그 연결이 없고, Spring Session JDBC 는
 * 만료 이벤트도 발행하지 않는다. 그래서 핸드셰이크 때 실은 HTTP 세션 ID({@link HttpSessionHandshakeInterceptor})로
 * 주기적으로 저장소를 확인해 세션이 없으면 연결을 닫는다.
 *
 * <p>닫히면 클라이언트(ws-client.js)는 재연결을 시도하고, 로그인이 없으니 구독이 거부돼 폴링으로 넘어가며,
 * common.js 의 상태 확인이 30초 안에 로그인 화면으로 보낸다. 방 참가자였다면 연결이 끊긴 것으로 나가기가 잡힌다(RoomPresenceService).
 *
 * <p>로그인 없이 연 연결(세션 ID 없음)은 판단하지 않는다.
 */
@Slf4j
@Component
@Profile("session-jdbc")
public class WebSocketHttpSessionGuard implements WebSocketHandlerDecoratorFactory, InitializingBean {

    private final SessionRepository<?> sessionRepository;
    private final TaskScheduler taskScheduler;
    private final long checkMs;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WebSocketHttpSessionGuard(SessionRepository<?> sessionRepository,
                                     @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                     @Value("${game.multi.ws-session-check-ms:30000}") long checkMs) {
        this.sessionRepository = sessionRepository;
        this.taskScheduler = taskScheduler;
        this.checkMs = checkMs;
    }

    @Override
    public void afterPropertiesSet() {
        taskScheduler.scheduleWithFixedDelay(this::closeConnectionsWithoutHttpSession, Duration.ofMillis(checkMs));
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    void closeConnectionsWithoutHttpSession() {
        for (WebSocketSession ws : sessions.values()) {
            Object httpSessionId = ws.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
            if (!(httpSessionId instanceof String id) || !ws.isOpen()) {
                continue;
            }
            // findById 는 만료된 세션을 null 로 돌려주고 지운다 — 정리 배치를 기다리지 않는다
            if (sessionRepository.findById(id) != null) {
                continue;
            }
            try {
                log.info("WebSocket closed: HTTP session ended wsSession={} user={}", ws.getId(),
                        ws.getPrincipal() != null ? ws.getPrincipal().getName() : null);
                ws.close(CloseStatus.POLICY_VIOLATION.withReason("HTTP session ended"));
            } catch (IOException e) {
                log.debug("WebSocket close failed: wsSession={}", ws.getId(), e);
            }
        }
    }

    int openConnectionCount() {
        return sessions.size();
    }
}
