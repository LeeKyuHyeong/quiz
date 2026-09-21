package com.kh.game.service;

import com.kh.game.security.CustomUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 멀티 참가자 접속 상태(presence) — 브라우저가 나가기 신호를 못 보내도(창 전체 닫기·강제 종료·네트워크 끊김) 방에서 나가게 한다.
 *
 * <p>판단 근거는 "그 (방, 회원)의 방 토픽 WebSocket 구독이 하나라도 살아 있는가" 다.
 * 구독하면 활성 연결 집합에 넣고, 연결이 끊겨 집합이 비면 {@code game.multi.disconnect-grace-ms} 뒤 나가기를 예약한다.
 * 그 사이 다시 구독하거나(새로고침·페이지 이동·재연결) 페이지를 열면 취소된다. 요청 도착 순서가 아니라 집합이 비었는가로
 * 판단하므로, 새 페이지의 구독이 옛 연결 종료보다 먼저 오든 늦게 오든 결과가 같다(2026-09-21 순서 결함과 다른 점).
 *
 * <p>한 번도 구독한 적 없는 참가자(WebSocket 이 안 돼 폴링만 하는 경우)는 판단 대상이 아니다 — 지금처럼 탭 닫기 신호에만 기댄다.
 *
 * <p>구독 이벤트는 인가 인터셉터를 통과해 채널에 보내진 구독에만 발행된다(StompSubProtocolHandler, spring-websocket 6.2).
 * 같은 연결의 구독·끊김 이벤트는 그 연결의 처리 순서대로 온다. 끊김 이벤트는 한 연결에 여러 번 올 수 있어 멱등이어야 한다.
 *
 * <p>인메모리(단일 인스턴스 운영, 페이지 토큰·대기 목록과 같은 전제). 배포로 프로세스가 바뀌면 비고, 클라이언트가 새 인스턴스에
 * 재연결·재구독하며 다시 채워진다. 종료 중인 프로세스의 끊김은 무시한다 — 배포 때 옛 인스턴스가 모두를 내보내지 않도록.
 */
@Slf4j
@Service
public class RoomPresenceService {

    static final String ROOM_TOPIC_PREFIX = "/topic/room/";

    /** 끊긴 채 이 시간이 지난 기록은 다음 이벤트 때 지운다 (재시작 때 되살릴 대상의 수명). */
    static final Duration ABSENT_TTL = Duration.ofHours(6);

    private final RoomUnloadService roomUnloadService;
    private final long disconnectGraceMs;

    /** "방:회원" → 살아 있는 WebSocket 세션 id. 비어 있으면 그 참가자는 연결이 없다(끊김 기록). */
    private final Map<String, Set<String>> sessionsByKey = new HashMap<>();
    /** 세션 id → 그 세션이 구독한 "방:회원" (끊김 이벤트에는 구독 대상이 없어서 필요) */
    private final Map<String, Set<String>> keysBySession = new HashMap<>();
    /** 집합이 빈 시각 — 재시작 때 남은 유예를 계산한다 */
    private final Map<String, Instant> absentSince = new HashMap<>();

    private volatile boolean shuttingDown;

    public RoomPresenceService(RoomUnloadService roomUnloadService,
                               @Value("${game.multi.disconnect-grace-ms:60000}") long disconnectGraceMs) {
        this.roomUnloadService = roomUnloadService;
        this.disconnectGraceMs = disconnectGraceMs;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String roomCode = roomCodeOf(accessor.getDestination());
        Long memberId = memberIdOf(event.getUser());
        String sessionId = accessor.getSessionId();
        if (roomCode == null || memberId == null || sessionId == null) {
            return;
        }
        subscribed(sessionId, roomCode, memberId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (shuttingDown) {
            return;
        }
        disconnected(event.getSessionId());
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        shuttingDown = true;
    }

    synchronized void subscribed(String sessionId, String roomCode, Long memberId) {
        String key = key(roomCode, memberId);
        sessionsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(sessionId);
        keysBySession.computeIfAbsent(sessionId, s -> new HashSet<>()).add(key);
        absentSince.remove(key);
        roomUnloadService.cancelLeave(roomCode, memberId);
        log.debug("Presence subscribe: key={} session={}", key, sessionId);
    }

    synchronized void disconnected(String sessionId) {
        Set<String> keys = keysBySession.remove(sessionId);
        if (keys == null) {
            return;  // 구독 없던 연결이거나 이미 처리한 끊김
        }
        Instant now = Instant.now();
        absentSince.values().removeIf(t -> t.isBefore(now.minus(ABSENT_TTL)));
        for (String key : keys) {
            Set<String> sessions = sessionsByKey.get(key);
            if (sessions == null) {
                continue;
            }
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessionsByKey.remove(key);
                absentSince.put(key, now);
                String[] parts = key.split(":", 2);
                roomUnloadService.scheduleLeaveOnDisconnect(parts[0], Long.valueOf(parts[1]), disconnectGraceMs);
                log.debug("Presence empty, leave scheduled: key={} graceMs={}", key, disconnectGraceMs);
            }
        }
    }

    /**
     * 재시작(FINISHED → WAITING) 직후 — 결과 화면에서 연결이 끊긴 참가자의 나가기를 다시 잡는다.
     * 종료된 방에서는 leaveRoom 이 무시되므로, 결과 화면에서 창을 닫은 사람이 재시작으로 대기실에 JOINED 로 되살아나
     * 준비를 못 해 아무도 시작하지 못하게 된다. 끊긴 지 유예가 이미 지났어도 재시작 직후 무시 구간보다는 늦게 잡는다.
     * 재시작한 방장은 제외한다(방금 요청을 보낸 사람).
     */
    public synchronized void onRestart(String roomCode, Long hostId) {
        Instant now = Instant.now();
        String prefix = roomCode + ":";
        long floorMs = GameRoomService.LEAVE_IGNORED_AFTER_RESTART.toMillis() + 1000;
        absentSince.forEach((key, since) -> {
            if (!key.startsWith(prefix)) {
                return;
            }
            Long memberId = Long.valueOf(key.substring(prefix.length()));
            if (memberId.equals(hostId)) {
                return;
            }
            long remainingMs = disconnectGraceMs - Duration.between(since, now).toMillis();
            roomUnloadService.rescheduleLeaveOnDisconnect(roomCode, memberId, Math.max(remainingMs, floorMs));
            log.debug("Presence restart, leave rescheduled: key={}", key);
        });
    }

    /** 이 참가자의 방 토픽 연결이 지금 살아 있는가 */
    public synchronized boolean isConnected(String roomCode, Long memberId) {
        return sessionsByKey.containsKey(key(roomCode, memberId));
    }

    private static String roomCodeOf(String destination) {
        if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
            return null;
        }
        String roomCode = destination.substring(ROOM_TOPIC_PREFIX.length());
        return roomCode.isEmpty() || roomCode.contains("/") || roomCode.contains(":") ? null : roomCode;
    }

    private static Long memberIdOf(Principal user) {
        if (user instanceof Authentication auth && auth.getPrincipal() instanceof CustomUserDetails details) {
            return details.getMemberId();
        }
        return null;
    }

    private static String key(String roomCode, Long memberId) {
        return roomCode + ":" + memberId;
    }
}
