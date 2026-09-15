package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
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
 * 종료된(FINISHED) 방에서 라운드 액션이 들어와도 게임 종료 처리가 다시 돌면 안 된다.
 *
 * 배경: nextRound 와 skipCurrentSong 은 방 상태를 확인하지 않았다. FINISHED 방에 한 번 더 들어오면
 * finishGame 이 재실행되어 전적·LP 가 이중 반영됐다 (2026-09-16 발견). startRound 만 상태를 확인하고 있었다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameService - FINISHED 방 라운드 액션 차단")
class MultiGameServiceFinishedRoomGuardTest {

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private MultiGameService multiGameService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private SongRepository songRepository;

    private Member host;
    private Member guest;
    private GameRoom room;
    private Song song;

    @BeforeEach
    void setUp() {
        host = createMember("fin_host");
        guest = createMember("fin_guest");
        room = gameRoomService.createRoom(host, "fin room", 4, 2, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
        gameRoomService.toggleReady(room, guest);
        multiGameService.startGame(room, host);

        Genre genre = new Genre();
        genre.setCode("FING");
        genre.setName("테스트장르");
        genre.setUseYn("Y");
        genre = genreRepository.save(genre);

        song = new Song();
        song.setTitle("정답노래");
        song.setArtist("테스트가수");
        song.setGenre(genre);
        song.setUseYn("Y");
        song.setIsSolo(false);
        song.setYoutubeVideoId("test" + System.nanoTime());
        song.setStartTime(0);
        song.setPlayDuration(30);
        song = songRepository.save(song);

        // 마지막 라운드가 진행 중인 상태에서 손님이 정답 → RESULT
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setCurrentRound(2);
        room.setCurrentSong(song);
        assertThat(multiGameService.sendChat(room, guest, "정답노래").get("isCorrect")).isEqualTo(true);

        // 마지막 라운드 뒤 "결과 보기" → 게임 종료 1회
        Map<String, Object> finish = multiGameService.nextRound(room, host);
        assertThat(finish.get("isGameOver")).isEqualTo(true);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.FINISHED);
        assertThat(memberRepository.findById(guest.getId()).orElseThrow().getMultiGames()).isEqualTo(1);
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

    @Test
    @DisplayName("FINISHED 방에 nextRound 가 다시 오면 거부되고 전적은 그대로다")
    void nextRound_onFinishedRoom_isRejectedWithoutDoubleCounting() {
        Map<String, Object> result = multiGameService.nextRound(room, host);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(memberRepository.findById(guest.getId()).orElseThrow().getMultiGames()).isEqualTo(1);
        assertThat(memberRepository.findById(host.getId()).orElseThrow().getMultiGames()).isEqualTo(1);
    }

    @Test
    @DisplayName("FINISHED 방에 skipCurrentSong 이 오면 거부된다")
    void skipCurrentSong_onFinishedRoom_isRejected() {
        // 종료 직전 재생 중이던 것처럼 phase 를 PLAYING 으로 두고 호출
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setWinner(null);

        Map<String, Object> result = multiGameService.skipCurrentSong(room, host, song.getId());

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(memberRepository.findById(guest.getId()).orElseThrow().getMultiGames()).isEqualTo(1);
    }
}
