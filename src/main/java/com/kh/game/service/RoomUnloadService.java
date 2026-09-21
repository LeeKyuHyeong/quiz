package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 페이지 언로드(탭 닫기·뒤로가기) 시 유예를 두고 방을 나가게 한다.
 *
 * 클라이언트는 pagehide 에서 navigator.sendBeacon 으로 /unload 를 보낸다. sendBeacon 은 CSRF 헤더를
 * 실을 수 없어 그 경로만 CSRF 예외이며, 그래서 즉시 나가지 않고 유예 뒤에 적용한다.
 *
 * <p>브라우저는 새 페이지를 받은 <b>뒤에</b> 옛 페이지의 pagehide 를 실행한다. 그래서 새로고침·대기실→플레이 같은
 * 게임 내 이동의 신호는 다음 페이지 GET 보다 늦게 도착한다(2026-09-21 운영: 게임 시작 8초 뒤 전원 이탈).
 * 도착 순서에 기대지 않도록 페이지 GET 마다 새 토큰을 발급하고({@link #issuePageToken}), 신호는 그 참가자의
 * <b>최신</b> 토큰일 때만 받는다. 옛 페이지·모르는 토큰(배포 전 페이지)·토큰 없음은 무시한다.
 *
 * <p>브라우저가 신호를 못 보내는 경우(창 전체 닫기·강제 종료·네트워크 끊김)는 {@link RoomPresenceService} 가 방 토픽
 * WebSocket 연결이 모두 끊긴 것을 보고 같은 대기 목록에 더 긴 유예로 예약한다. 페이지 GET·재참가·재구독이 어느 쪽이든 취소한다.
 *
 * <p>토큰과 대기 목록은 인메모리(단일 인스턴스 운영). 배포로 프로세스가 바뀌면 둘 다 사라지고, 그 전에 열린
 * 페이지의 신호는 무시되어 그 참가자는 다음 명시적 나가기·정리 배치·cleanupStaleParticipations 에서 정리된다.
 */
@Slf4j
@Service
public class RoomUnloadService {

    private final GameRoomService gameRoomService;
    private final MultiGameService multiGameService;
    private final GameBroadcastService gameBroadcastService;
    private final GameRoomRepository gameRoomRepository;
    private final MemberRepository memberRepository;
    private final TaskScheduler taskScheduler;
    private final TransactionTemplate transactionTemplate;
    private final long graceMs;

    /** 발급 후 이 시간이 지난 토큰은 다음 발급 때 지운다 (열어 둔 채 방치된 페이지는 정리 배치 몫). */
    static final Duration TOKEN_TTL = Duration.ofHours(6);

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PageToken> latestTokens = new ConcurrentHashMap<>();

    private record PageToken(String value, Instant issuedAt) {}

    /** 예약된 나가기. 무엇이 예약했는지에 따라 취소 규칙이 다르다(폴링은 연결 끊김 예약만 취소). */
    private record Pending(ScheduledFuture<?> future, Reason reason, Object id) {}

    enum Reason { UNLOAD_SIGNAL, DISCONNECT }

    public RoomUnloadService(GameRoomService gameRoomService,
                             MultiGameService multiGameService,
                             GameBroadcastService gameBroadcastService,
                             GameRoomRepository gameRoomRepository,
                             MemberRepository memberRepository,
                             TaskScheduler taskScheduler,
                             TransactionTemplate transactionTemplate,
                             @Value("${game.multi.unload-grace-ms:8000}") long graceMs) {
        this.gameRoomService = gameRoomService;
        this.multiGameService = multiGameService;
        this.gameBroadcastService = gameBroadcastService;
        this.gameRoomRepository = gameRoomRepository;
        this.memberRepository = memberRepository;
        this.taskScheduler = taskScheduler;
        this.transactionTemplate = transactionTemplate;
        this.graceMs = graceMs;
    }

    /**
     * 페이지 GET(대기실·플레이·결과) — 유예 중인 나가기를 취소하고 이 페이지의 토큰을 발급한다.
     * 이전 페이지의 토큰은 이 시점부터 무효다.
     */
    public String issuePageToken(String roomCode, Long memberId) {
        cancelLeave(roomCode, memberId);
        Instant now = Instant.now();
        latestTokens.values().removeIf(t -> t.issuedAt().isBefore(now.minus(TOKEN_TTL)));
        String token = UUID.randomUUID().toString();
        latestTokens.put(key(roomCode, memberId), new PageToken(token, now));
        return token;
    }

    /**
     * 유예 뒤 나가기를 예약한다. 신호를 보낸 페이지가 그 참가자의 최신 페이지일 때만 — 아니면 무시하고 false.
     * 같은 사람의 이전 예약은 새 예약으로 대체된다.
     */
    public boolean scheduleLeave(String roomCode, Long memberId, String token) {
        String key = key(roomCode, memberId);
        PageToken latest = latestTokens.get(key);
        if (token == null || latest == null || !latest.value().equals(token)) {
            log.debug("Unload ignored (not the latest page): roomCode={} memberId={}", roomCode, memberId);
            return false;
        }
        replacePending(key, roomCode, memberId, graceMs, Reason.UNLOAD_SIGNAL);
        return true;
    }

    /**
     * WebSocket 연결이 모두 끊긴 참가자의 나가기를 delayMs 뒤로 예약한다 ({@link RoomPresenceService}).
     * 이미 예약된 나가기가 있으면 그대로 둔다 — 탭 닫기 신호(8초)로 잡힌 더 이른 나가기를 늦추지 않는다.
     */
    public void scheduleLeaveOnDisconnect(String roomCode, Long memberId, long delayMs) {
        String key = key(roomCode, memberId);
        if (pending.containsKey(key)) {
            return;
        }
        replacePending(key, roomCode, memberId, delayMs, Reason.DISCONNECT);
    }

    /** 연결 끊김 나가기를 delayMs 뒤로 다시 잡는다 (재시작 — 종료된 방에서 무시된 나가기를 되살린다). */
    public void rescheduleLeaveOnDisconnect(String roomCode, Long memberId, long delayMs) {
        replacePending(key(roomCode, memberId), roomCode, memberId, delayMs, Reason.DISCONNECT);
    }

    // synchronized: 예약이 대기 목록에 들어가기 전에 작업이 먼저 실행돼도(delay 0) claim 이 기다리게 한다
    private synchronized void replacePending(String key, String roomCode, Long memberId, long delayMs, Reason reason) {
        Object id = new Object();
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> applyLeave(key, id, roomCode, memberId),
                Instant.now().plusMillis(Math.max(0, delayMs)));
        Pending previous = pending.put(key, new Pending(future, reason, id));
        if (previous != null) {
            previous.future().cancel(false);
        }
    }

    /** 유예 중인 나가기를 취소한다 (페이지 재진입). 취소한 것이 있으면 true. */
    public boolean cancelLeave(String roomCode, Long memberId) {
        Pending removed = pending.remove(key(roomCode, memberId));
        if (removed == null) {
            return false;
        }
        removed.future().cancel(false);
        return true;
    }

    /** 연결 끊김으로 예약된 나가기만 취소한다 (폴링 요청 — 탭 닫기 신호로 잡힌 나가기는 건드리지 않는다). */
    public boolean cancelDisconnectLeave(String roomCode, Long memberId) {
        String key = key(roomCode, memberId);
        Pending current = pending.get(key);
        if (current == null || current.reason() != Reason.DISCONNECT || !pending.remove(key, current)) {
            return false;
        }
        current.future().cancel(false);
        return true;
    }

    /** 이 예약이 아직 대기 목록의 현재 예약이면 꺼내고 true — 취소·대체됐으면 false. */
    private synchronized boolean claim(String key, Object id) {
        Pending current = pending.get(key);
        return current != null && current.id() == id && pending.remove(key, current);
    }

    private void applyLeave(String key, Object id, String roomCode, Long memberId) {
        // 취소·대체와 경합: 실행 직전에 취소됐거나 새 예약으로 바뀌었으면 아무것도 하지 않는다
        if (!claim(key, id)) {
            return;
        }
        latestTokens.remove(key);
        try {
            Map<String, Object> payloads = transactionTemplate.execute(status -> {
                GameRoom room = gameRoomRepository.findByRoomCode(roomCode).orElse(null);
                Member member = memberRepository.findById(memberId).orElse(null);
                if (room == null || member == null) {
                    return null;
                }
                gameRoomService.leaveRoom(room, member);
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("room", gameRoomService.buildRoomStatus(room));
                if (room.getStatus() == GameRoom.RoomStatus.PLAYING) {
                    result.put("round", multiGameService.getCurrentRoundInfo(room));
                }
                return result;
            });
            if (payloads == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> roomStatus = (Map<String, Object>) payloads.get("room");
            gameBroadcastService.broadcastRoomUpdate(roomCode, roomStatus);
            @SuppressWarnings("unchecked")
            Map<String, Object> roundInfo = (Map<String, Object>) payloads.get("round");
            if (roundInfo != null) {
                gameBroadcastService.broadcastRoundUpdate(roomCode, roundInfo);
            }
            log.debug("Unload leave applied: roomCode={} memberId={}", roomCode, memberId);
        } catch (Exception e) {
            log.warn("Unload leave failed: roomCode={} memberId={}", roomCode, memberId, e);
        }
    }

    private static String key(String roomCode, Long memberId) {
        return roomCode + ":" + memberId;
    }
}
