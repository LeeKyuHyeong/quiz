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

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PageToken> latestTokens = new ConcurrentHashMap<>();

    private record PageToken(String value, Instant issuedAt) {}

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
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> applyLeave(key, roomCode, memberId),
                Instant.now().plusMillis(graceMs));
        ScheduledFuture<?> previous = pending.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
        return true;
    }

    /** 유예 중인 나가기를 취소한다 (페이지 재진입). 취소한 것이 있으면 true. */
    public boolean cancelLeave(String roomCode, Long memberId) {
        ScheduledFuture<?> future = pending.remove(key(roomCode, memberId));
        if (future == null) {
            return false;
        }
        future.cancel(false);
        return true;
    }

    private void applyLeave(String key, String roomCode, Long memberId) {
        // 취소와 경합: 실행 직전에 취소됐으면 아무것도 하지 않는다
        if (pending.remove(key) == null) {
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
