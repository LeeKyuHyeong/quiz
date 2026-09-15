package com.kh.game.controller.client;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.repository.SongRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GameBroadcastService;
import com.kh.game.service.GameRoomService;
import com.kh.game.service.MultiGameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 CHAT push 페이로드는 폴링 GET /chats 의 항목과 같은 형태여야 한다.
 *
 * 배경: push 는 nickname/message/messageType/createdAt 만 실었고 정답 타입을 "CORRECT" 로 보냈다.
 * 클라이언트는 memberId 로 내 메시지, isHost 로 왕관, "CORRECT_ANSWER" 로 정답 강조, id 로 lastChatId 를
 * 판단하므로 WebSocket 사용자에게는 셋 다 빠졌고, 폴링으로 넘어가면 채팅이 중복됐다 (2026-09-16 발견).
 * 어제의 ROOM_UPDATE(success 키) 건과 같은 유형의 push/polling 계약 불일치.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameController - 채팅 push 페이로드 계약")
class MultiGameControllerChatPushTest {

    @Autowired
    private MockMvc mockMvc;

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

    @MockBean
    private GameBroadcastService gameBroadcastService;

    private Member host;
    private Member guest;
    private GameRoom room;

    @BeforeEach
    void setUp() {
        host = createMember("chat_host");
        guest = createMember("chat_guest");
        room = gameRoomService.createRoom(host, "chat room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
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

    /** 방을 라운드 진행 중(PLAYING/PLAYING, 현재 곡 있음)으로 만든다 */
    private Song stagePlayingRound(String title) {
        Genre genre = new Genre();
        genre.setCode("TESTG");
        genre.setName("테스트장르");
        genre.setUseYn("Y");
        genre = genreRepository.save(genre);

        Song song = new Song();
        song.setTitle(title);
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
        return song;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendAndCapturePush(Member sender, String message) throws Exception {
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/chat")
                        .with(user(new CustomUserDetails(sender)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 정답이면 "정답: ..." 시스템 메시지도 같이 push 되므로 보낸 사람의 채팅만 고른다
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gameBroadcastService, atLeastOnce()).broadcastChat(eq(room.getRoomCode()), captor.capture());
        return captor.getAllValues().stream()
                .filter(p -> !"SYSTEM".equals(p.get("messageType")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("일반 채팅 push 에 id·memberId·isHost 가 실린다")
    void chatPush_carriesIdMemberIdAndHostFlag() throws Exception {
        Map<String, Object> push = sendAndCapturePush(host, "hello");

        assertThat(push.get("id")).isNotNull();
        assertThat(push.get("memberId")).isEqualTo(host.getId());
        assertThat(push.get("isHost")).isEqualTo(true);
        assertThat(push.get("messageType")).isEqualTo("CHAT");
        assertThat(push.get("nickname")).isEqualTo(host.getNickname());
        assertThat(push.get("message")).isEqualTo("hello");
    }

    @Test
    @DisplayName("정답 채팅 push 의 messageType 은 폴링과 같은 CORRECT_ANSWER 다")
    void correctAnswerPush_usesPollingMessageType() throws Exception {
        stagePlayingRound("정답노래");

        Map<String, Object> push = sendAndCapturePush(guest, "정답노래");

        assertThat(push.get("messageType")).isEqualTo("CORRECT_ANSWER");
        assertThat(push.get("memberId")).isEqualTo(guest.getId());
        assertThat(push.get("isHost")).isEqualTo(false);
    }

    /**
     * 배경: 시스템 메시지("게임 시작", "라운드 N", "정답: ...")는 DB 에만 저장되고 push 되지 않았다.
     * WebSocket 이 살아 있으면 채팅 폴링을 돌리지 않으므로 그 사용자에게는 아예 보이지 않았다 (2026-09-16 발견).
     */
    @Test
    @DisplayName("게임 시작 시스템 메시지가 CHAT push 로 나간다")
    void systemMessage_isPushedAsChat() throws Exception {
        gameRoomService.toggleReady(room, guest);

        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/start")
                        .with(user(new CustomUserDetails(host)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gameBroadcastService).broadcastChat(eq(room.getRoomCode()), captor.capture());
        Map<String, Object> push = captor.getValue();

        assertThat(push.get("messageType")).isEqualTo("SYSTEM");
        assertThat((String) push.get("message")).contains("게임이 시작");
        assertThat(push.get("id")).isNotNull();
    }

    @Test
    @DisplayName("push 페이로드의 키는 GET /chats 항목의 키와 같다")
    void chatPush_keysMatchPollingEntry() throws Exception {
        Map<String, Object> push = sendAndCapturePush(guest, "contract");

        List<Map<String, Object>> polled = multiGameService.getChats(room, 0L);
        Map<String, Object> last = polled.get(polled.size() - 1);

        assertThat(push.keySet()).isEqualTo(last.keySet());
        assertThat(push.get("id")).isEqualTo(last.get("id"));
    }
}
