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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 페이지 언로드(탭 닫기·뒤로가기) 시 유예를 두고 방을 나가게 한다.
 *
 * 클라이언트는 beforeunload/pagehide 에서 navigator.sendBeacon 으로 /unload 를 보낸다. sendBeacon 은
 * CSRF 헤더를 실을 수 없어 그 경로만 CSRF 예외이며, 그래서 즉시 나가지 않고 유예 뒤에 적용한다.
 * 새로고침이나 대기실→플레이→결과 같은 게임 내 이동도 언로드를 일으키므로, 다음 페이지 GET
 * ({@link #cancelLeave})이 유예 중인 나가기를 취소한다.
 *
 * 대기 목록은 인메모리(단일 인스턴스 운영). 배포로 프로세스가 바뀌면 유예 중이던 나가기는 사라지고,
 * 그 참가자는 다음 명시적 나가기·정리 배치·cleanupStaleParticipations 에서 정리된다.
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

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

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

    /** 유예 뒤 나가기를 예약한다. 같은 사람의 이전 예약은 새 예약으로 대체된다. */
    public void scheduleLeave(String roomCode, Long memberId) {
        String key = key(roomCode, memberId);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> applyLeave(key, roomCode, memberId),
                Instant.now().plusMillis(graceMs));
        ScheduledFuture<?> previous = pending.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
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
