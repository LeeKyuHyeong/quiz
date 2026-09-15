package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 방 정원은 활성 참가자(LEFT 제외) 기준이어야 한다.
 *
 * 배경: 나가기·강퇴·로비 복귀는 참가자 행을 지우지 않고 LEFT 로만 바꾸는데,
 * 정원 계산이 행 수를 세는 바람에 한 번 나간 자리는 다시 채울 수 없었다 (2026-09-16 발견).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("GameRoom 정원 - 나간 참가자는 정원에서 빠진다")
class GameRoomCapacityTest {

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member host;
    private Member guest1;
    private Member guest2;

    @BeforeEach
    void setUp() {
        host = createMember("cap_host");
        guest1 = createMember("cap_guest1");
        guest2 = createMember("cap_guest2");
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

    /** 2인 방: 방장 + 손님1 → 손님1 나감 → 손님2 */
    private GameRoom twoSeatRoomWithOneLeaver() {
        GameRoom room = gameRoomService.createRoom(host, "cap room", 2, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest1);
        gameRoomService.leaveRoom(room, guest1);
        return room;
    }

    @Test
    @DisplayName("나간 사람의 자리는 다른 사람이 다시 채울 수 있다")
    void leftSeat_canBeTakenAgain() {
        GameRoom room = twoSeatRoomWithOneLeaver();

        assertThatCode(() -> gameRoomService.joinRoom(room.getRoomCode(), guest2))
                .doesNotThrowAnyException();

        GameRoomParticipant p = participantRepository.findByGameRoomAndMember(room, guest2).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("현재 인원은 LEFT 를 제외한 수다")
    void currentPlayerCount_excludesLeft() {
        GameRoom room = twoSeatRoomWithOneLeaver();

        assertThat(room.getCurrentPlayerCount()).isEqualTo(1);
        assertThat(room.canJoin()).isTrue();
    }

    @Test
    @DisplayName("로비 목록 쿼리도 LEFT 를 제외하고 정원을 판단한다")
    void availableRooms_ignoreLeftParticipants() {
        GameRoom room = twoSeatRoomWithOneLeaver();

        assertThat(gameRoomRepository.findAvailableRooms())
                .extracting(GameRoom::getRoomCode)
                .contains(room.getRoomCode());
    }
}
