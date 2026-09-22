package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 이벤트(ApplicationReadyEvent)가 지연 뒤 정리를 예약하는 경로 (O-022). 이 컨텍스트만 정리를 켠다.
 * 컨텍스트가 뜨면 지연이 시작되므로 테스트는 하나만 두고, 방은 지연 안에 만든다(회원 2·방 1 = 1초 미만).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "game.multi.startup-sweep-delay-ms=5000")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("기동 뒤 정리 - 기동 이벤트가 지연 뒤 연결 없는 참가자를 내보낸다")
class RoomPresenceStartupSweepReadyTest {

    @Autowired private GameRoomService gameRoomService;
    @Autowired private RoomPresenceService presence;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GameRoomRepository gameRoomRepository;
    @Autowired private GameRoomParticipantRepository participantRepository;
    @Autowired private GameRoomChatRepository chatRepository;

    private GameRoom room;
    private final List<Member> members = new ArrayList<>();

    @AfterEach
    void tearDown() {
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
    @DisplayName("기동 5초 뒤 정리가 돌아 연결 없는 참가자는 나가고 구독 중인 방장은 남는다")
    void readyEvent_schedulesSweep(CapturedOutput output) {
        Member host = createMember("rh");
        Member guest = createMember("rg");
        room = gameRoomService.createRoom(host, "ready room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
        presence.subscribed("s-ready-host", room.getRoomCode(), host.getId());
        assertThat(output).contains("Presence startup sweep scheduled: delayMs=5000");

        assertThat(waitUntil(() -> statusOf(guest) == GameRoomParticipant.ParticipantStatus.LEFT, 15000))
                .as("지연 5초 + 유예 뒤 나감").isTrue();

        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
        assertThat(output).contains("Presence startup sweep: participantsWithoutConnection=1");
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
        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        return participantRepository.findByGameRoomAndMember(r, member).orElseThrow().getStatus();
    }

    private static boolean waitUntil(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return condition.getAsBoolean();
    }
}
