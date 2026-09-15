package com.kh.game.service;

import com.kh.game.batch.RoomCleanupBatch;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 게임 진행 중(PLAYING) 나가기는 실제로 처리되어야 한다.
 *
 * 배경: leaveRoom 이 PLAYING 이면 무시하고 성공을 돌려줘서, 플레이 화면의 "게임 종료" 를 눌러도
 * 참가자가 PLAYING 으로 남았다. 그 사람은 다른 방을 만들거나 참가할 수 없었고("이미 다른 방에 참가중"),
 * 로비 배너는 JOINED 만 찾아 원인도 보여주지 못했다. 방장이 사라진 PLAYING 방은 정리 배치 대상도 아니었다
 * (2026-09-16 발견).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("GameRoom 게임 중 나가기")
class GameRoomLeaveDuringPlayTest {

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private MultiGameService multiGameService;

    @Autowired
    private RoomCleanupBatch roomCleanupBatch;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    private Member host;
    private Member guest1;
    private Member guest2;

    @BeforeEach
    void setUp() {
        host = createMember("play_host");
        guest1 = createMember("play_guest1");
        guest2 = createMember("play_guest2");
    }

    private Member createMember(String name) {
        Member m = new Member();
        m.setUsername(name);
        m.setNickname(name);
        m.setEmail(name + "@test.com");
        m.setPassword("encoded");
        m.setRole(Member.MemberRole.USER);
        m.setStatus(Member.MemberStatus.ACTIVE);
        return memberRepository.save(m);
    }

    /** 방장 + 손님 2명이 준비를 마치고 게임을 시작한 PLAYING 방 */
    private GameRoom playingRoom() {
        GameRoom room = gameRoomService.createRoom(host, "play room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest1);
        gameRoomService.joinRoom(room.getRoomCode(), guest2);
        gameRoomService.toggleReady(room, guest1);
        gameRoomService.toggleReady(room, guest2);
        multiGameService.startGame(room, host);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
        return room;
    }

    private GameRoomParticipant participantOf(GameRoom room, Member member) {
        return participantRepository.findByGameRoomAndMember(room, member).orElseThrow();
    }

    @Test
    @DisplayName("참가자가 게임 중 나가면 LEFT 가 되고 방은 계속 진행된다")
    void guestLeavesDuringPlay_isMarkedLeft() {
        GameRoom room = playingRoom();

        gameRoomService.leaveRoom(room, guest1);

        assertThat(participantOf(room, guest1).getStatus()).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
        assertThat(room.isHost(host)).isTrue();
    }

    @Test
    @DisplayName("게임 중 나간 사람은 바로 새 방을 만들 수 있다")
    void afterLeavingDuringPlay_canCreateNewRoom() {
        GameRoom room = playingRoom();

        gameRoomService.leaveRoom(room, guest1);

        assertThat(participantRepository.findActiveParticipation(guest1)).isEmpty();
        assertThatCode(() -> gameRoomService.createRoom(guest1, "next room", 4, 5, false, "{}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("방장이 게임 중 나가면 남은 참가자에게 방장이 넘어간다")
    void hostLeavesDuringPlay_delegatesToRemainingPlayer() {
        GameRoom room = playingRoom();

        gameRoomService.leaveRoom(room, host);

        assertThat(participantOf(room, host).getStatus()).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
        assertThat(room.isHost(host)).isFalse();
        assertThat(room.isHost(guest1) || room.isHost(guest2)).isTrue();
    }

    @Test
    @DisplayName("마지막 남은 사람(방장)이 게임 중 나가면 방이 종료된다")
    void lastPlayerLeavesDuringPlay_finishesRoom() {
        GameRoom room = playingRoom();
        gameRoomService.leaveRoom(room, guest1);
        gameRoomService.leaveRoom(room, guest2);

        gameRoomService.leaveRoom(room, host);

        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.FINISHED);
    }

    @Test
    @DisplayName("로비 배너 조회는 PLAYING 참가자의 방도 돌려준다")
    void activeRoomByMember_includesPlayingParticipant() {
        GameRoom room = playingRoom();

        assertThat(gameRoomRepository.findActiveRoomByMember(guest1))
                .map(GameRoom::getRoomCode)
                .contains(room.getRoomCode());
    }

    @Test
    @DisplayName("정리 배치는 2시간 넘게 변화 없는 PLAYING 방을 종료한다")
    void cleanupBatch_finishesStalePlayingRoom() {
        GameRoom room = playingRoom();
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE game_room SET updated_at = :t WHERE id = :id")
                .setParameter("t", LocalDateTime.now().minusHours(3))
                .setParameter("id", room.getId())
                .executeUpdate();
        entityManager.clear();

        roomCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);

        GameRoom reloaded = gameRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GameRoom.RoomStatus.FINISHED);
    }

    @Test
    @DisplayName("정리 배치는 최근에 진행 중인 PLAYING 방은 건드리지 않는다")
    void cleanupBatch_keepsRecentPlayingRoom() {
        GameRoom room = playingRoom();
        entityManager.flush();
        entityManager.clear();

        roomCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);

        GameRoom reloaded = gameRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
    }
}
