package com.kh.game.repository;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("GameRoomParticipantRepository - 활성 참가자 exists 쿼리")
class GameRoomParticipantRepositoryTest {

    private static final Set<GameRoomParticipant.ParticipantStatus> ACTIVE =
            EnumSet.of(GameRoomParticipant.ParticipantStatus.JOINED, GameRoomParticipant.ParticipantStatus.PLAYING);

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member host;
    private Member joined;
    private Member playing;
    private Member left;
    private Member outsider;
    private GameRoom room;

    @BeforeEach
    void setUp() {
        host = createMember("host@test.com");
        joined = createMember("joined@test.com");
        playing = createMember("playing@test.com");
        left = createMember("left@test.com");
        outsider = createMember("outsider@test.com");

        room = new GameRoom();
        room.setRoomCode("ROOM01");
        room.setRoomName("test room");
        room.setHost(host);
        room = gameRoomRepository.save(room);

        createParticipant(room, host, GameRoomParticipant.ParticipantStatus.JOINED);
        createParticipant(room, joined, GameRoomParticipant.ParticipantStatus.JOINED);
        createParticipant(room, playing, GameRoomParticipant.ParticipantStatus.PLAYING);
        createParticipant(room, left, GameRoomParticipant.ParticipantStatus.LEFT);
    }

    private Member createMember(String email) {
        Member m = new Member();
        m.setEmail(email);
        m.setPassword("encoded");
        m.setNickname(email.substring(0, email.indexOf('@')));
        m.setUsername(email.substring(0, email.indexOf('@')));
        m.setRole(Member.MemberRole.USER);
        m.setStatus(Member.MemberStatus.ACTIVE);
        return memberRepository.save(m);
    }

    private void createParticipant(GameRoom room, Member member, GameRoomParticipant.ParticipantStatus status) {
        GameRoomParticipant p = new GameRoomParticipant(room, member);
        p.setStatus(status);
        participantRepository.save(p);
    }

    private boolean isActive(String roomCode, Member member) {
        return participantRepository.existsByGameRoomRoomCodeAndMemberIdAndStatusIn(roomCode, member.getId(), ACTIVE);
    }

    @Test
    @DisplayName("JOINED / PLAYING 참가자는 true")
    void activeStatuses_true() {
        assertThat(isActive("ROOM01", host)).isTrue();
        assertThat(isActive("ROOM01", joined)).isTrue();
        assertThat(isActive("ROOM01", playing)).isTrue();
    }

    @Test
    @DisplayName("LEFT 참가자(강퇴·퇴장)는 false")
    void leftStatus_false() {
        assertThat(isActive("ROOM01", left)).isFalse();
    }

    @Test
    @DisplayName("미참가 회원은 false")
    void outsider_false() {
        assertThat(isActive("ROOM01", outsider)).isFalse();
    }

    @Test
    @DisplayName("다른 방 코드 / 없는 방 코드는 false")
    void otherRoomCode_false() {
        GameRoom other = new GameRoom();
        other.setRoomCode("ROOM02");
        other.setRoomName("other room");
        other.setHost(outsider);
        gameRoomRepository.save(other);

        assertThat(isActive("ROOM02", joined)).isFalse();
        assertThat(isActive("NOPE99", joined)).isFalse();
    }
}
