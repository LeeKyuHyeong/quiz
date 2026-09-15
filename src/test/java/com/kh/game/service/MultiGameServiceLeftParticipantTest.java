package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 나갔거나 강퇴된(LEFT) 참가자의 게임 액션은 거부되어야 한다.
 *
 * 배경: 어제(06a6e4a) 구독과 폴링 GET 은 활성 참가자만 허용하도록 막았지만, POST /chat·/skip-vote 는
 * 상태를 보지 않는 findByGameRoomAndMember 로 참가자를 찾았다. 강퇴된 사람이 정답을 보내면 점수가 올라갔다
 * (2026-09-16 발견).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameService - LEFT 참가자 액션 거부")
class MultiGameServiceLeftParticipantTest {

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private MultiGameService multiGameService;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private SongRepository songRepository;

    private Member host;
    private Member guest;
    private GameRoom room;

    @BeforeEach
    void setUp() {
        host = createMember("left_host");
        guest = createMember("left_guest");
        room = gameRoomService.createRoom(host, "left room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);

        Genre genre = new Genre();
        genre.setCode("LEFTG");
        genre.setName("테스트장르");
        genre.setUseYn("Y");
        genre = genreRepository.save(genre);

        Song song = new Song();
        song.setTitle("정답노래");
        song.setArtist("테스트가수");
        song.setGenre(genre);
        song.setUseYn("Y");
        song.setIsSolo(false);
        song.setYoutubeVideoId("test" + System.nanoTime());
        song.setStartTime(0);
        song.setPlayDuration(30);
        song = songRepository.save(song);

        room.setStatus(GameRoom.RoomStatus.PLAYING);
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setCurrentRound(1);
        room.setCurrentSong(song);

        // 손님 강퇴 → LEFT
        gameRoomService.kickParticipant(room, host, guest);
        assertThat(participantOf(guest).getStatus()).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
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

    private GameRoomParticipant participantOf(Member member) {
        return participantRepository.findByGameRoomAndMember(room, member).orElseThrow();
    }

    @Test
    @DisplayName("LEFT 참가자의 정답 채팅은 거부되고 점수도 오르지 않는다")
    void sendChat_fromLeftParticipant_isRejected() {
        Map<String, Object> result = multiGameService.sendChat(room, guest, "정답노래");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(participantOf(guest).getScore()).isZero();
        assertThat(room.getWinner()).isNull();
        assertThat(room.getRoundPhase()).isEqualTo(GameRoom.RoundPhase.PLAYING);
    }

    @Test
    @DisplayName("LEFT 참가자의 포기 투표는 거부된다")
    void voteSkipRound_fromLeftParticipant_isRejected() {
        Map<String, Object> result = multiGameService.voteSkipRound(room, guest);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(participantOf(guest).getSkipVote()).isFalse();
    }
}
