package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 뒤 정리(startup sweep) 계약 — 서버가 내려가 있는 사이 창을 닫은 참가자는 신호도 새 인스턴스의 구독도 없어
 * 유령으로 남았다(2026-09-22 O-022). 기동 뒤 연결 없는 참가자의 나가기를 끊김 유예로 예약하고, 살아 있는 화면은 취소한다.
 *
 * 테스트 프로필은 정리를 꺼 두므로(컨텍스트가 스위트 내내 캐시됨) 여기서는 정리를 직접 부른다.
 * 기동 이벤트로 예약되는 경로는 {@link RoomPresenceStartupSweepReadyTest}.
 * 지연 처리가 스케줄러 스레드에서 별도 트랜잭션으로 돌므로 @Transactional 을 쓰지 않고 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("기동 뒤 정리 - 연결 없는 참가자는 유예 뒤 나가고, 살아 있는 화면은 남는다")
class RoomPresenceStartupSweepTest {

    @Value("${game.multi.disconnect-grace-ms}")
    private long graceMs;

    @Autowired private GameRoomService gameRoomService;
    @Autowired private RoomPresenceService presence;
    @Autowired private RoomUnloadService roomUnloadService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GameRoomRepository gameRoomRepository;
    @Autowired private GameRoomParticipantRepository participantRepository;
    @Autowired private GameRoomChatRepository chatRepository;

    private Member host;
    private Member guest;
    private GameRoom room;
    private final List<Member> members = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = createMember("sh");
        guest = createMember("sg");
        room = gameRoomService.createRoom(host, "sweep room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
        // 방장은 구독 중(살아 있는 화면), 손님은 서버가 내려간 사이 창을 닫아 연결이 없다
        presence.subscribed("s-host-" + System.nanoTime(), room.getRoomCode(), host.getId());
    }

    @AfterEach
    void tearDown() {
        roomUnloadService.cancelLeave(room.getRoomCode(), host.getId());
        roomUnloadService.cancelLeave(room.getRoomCode(), guest.getId());
        sleep(graceMs * 2);  // 이미 실행 중인 나가기가 방을 지우기 전에 끝나게
        GameRoom r = gameRoomRepository.findById(room.getId()).orElse(null);
        if (r != null) {
            chatRepository.deleteByGameRoom(r);
            gameRoomRepository.delete(r);
        }
        for (Member m : members) {
            memberRepository.deleteById(m.getId());
        }
    }

    @Test
    @DisplayName("연결이 없는 참가자는 유예 뒤 나가고, 구독 중인 참가자는 남는다")
    void sweep_leavesParticipantWithoutConnection_keepsSubscribed(CapturedOutput output) {
        presence.sweepAfterStartup();

        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), guest.getId())).as("연결 없는 참가자는 예약됨").isTrue();
        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), host.getId())).as("구독 중인 참가자는 예약 안 됨").isFalse();
        assertThat(waitUntil(() -> statusOf(guest) == GameRoomParticipant.ParticipantStatus.LEFT, graceMs * 10)).isTrue();
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
        assertThat(reloadRoom().getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
        assertThat(output).contains("Presence startup sweep: participantsWithoutConnection=1")
                .contains("Room leave applied: reason=DISCONNECT roomCode=" + room.getRoomCode());
    }

    @Test
    @DisplayName("폴링으로 살아 있는 화면(연결 끊김 취소)은 나가지 않는다")
    void sweep_thenPollingCancel_keepsParticipant() {
        presence.sweepAfterStartup();
        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), guest.getId())).isTrue();

        assertThat(roomUnloadService.cancelDisconnectLeave(room.getRoomCode(), guest.getId())).as("폴링 GET 이 하는 취소").isTrue();

        sleep(graceMs * 3);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("종료된 방(결과 화면)의 참가자는 대상이 아니다")
    void sweep_ignoresFinishedRooms() {
        GameRoom r = reloadRoom();
        r.setStatus(GameRoom.RoomStatus.FINISHED);
        gameRoomRepository.save(r);

        presence.sweepAfterStartup();

        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), guest.getId())).isFalse();
        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), host.getId())).isFalse();
    }

    @Test
    @DisplayName("지연이 음수면 기동 이벤트가 정리를 예약하지 않는다 (테스트 프로필 기본)")
    void negativeDelay_disablesSweep(CapturedOutput output) {
        presence.onApplicationReady();

        assertThat(output).contains("Presence startup sweep disabled");
        sleep(graceMs * 2);
        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), guest.getId())).isFalse();
    }

    private Member createMember(String prefix) {
        Member m = new Member();
        String name = prefix + (System.nanoTime() % 100000000);
        m.setUsername(name);
        m.setNickname(name);
        m.setEmail(name + "@test.com");
        m.setPassword("x");
        m.setRole(Member.MemberRole.USER);
        m.setStatus(Member.MemberStatus.ACTIVE);
        Member saved = memberRepository.save(m);
        members.add(saved);
        return saved;
    }

    private GameRoomParticipant.ParticipantStatus statusOf(Member member) {
        return participantRepository.findByGameRoomAndMember(reloadRoom(), member).orElseThrow().getStatus();
    }

    private GameRoom reloadRoom() {
        return gameRoomRepository.findById(room.getId()).orElseThrow();
    }

    private static boolean waitUntil(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
