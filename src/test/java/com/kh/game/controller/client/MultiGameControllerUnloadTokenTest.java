package com.kh.game.controller.client;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GameRoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 언로드 나가기는 "그 신호를 보낸 페이지가 아직 최신인가" 로 판단한다 (페이지 토큰).
 *
 * 배경(2026-09-21 운영): 브라우저는 새 페이지를 받은 뒤에 옛 페이지의 pagehide 를 실행한다. 그래서 새로고침·
 * 대기실→플레이 이동 때 옛 페이지의 sendBeacon 이 새 페이지 GET(=유예 중인 나가기 취소)보다 늦게 도착해
 * 다시 예약되고, 8초 뒤 참가자가 전원 빠져 게임이 끝났다. 도착 순서에 기대지 않도록, 페이지 GET 마다 새 토큰을
 * 발급하고 신호는 최신 토큰일 때만 받는다. 모르는 토큰(배포 전 페이지)·토큰 없음은 무시한다.
 *
 * 지연 처리가 스케줄러 스레드에서 별도 트랜잭션으로 돌므로 @Transactional 을 쓰지 않고 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MultiGameController - 언로드 나가기 페이지 토큰")
class MultiGameControllerUnloadTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    @Autowired
    private GameRoomChatRepository chatRepository;

    @Value("${game.multi.unload-grace-ms}")
    private long graceMs;

    private Member host;
    private Member guest;
    private GameRoom room;

    @BeforeEach
    void setUp() {
        host = createMember("token_host");
        guest = createMember("token_guest");
        room = gameRoomService.createRoom(host, "token room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
    }

    @AfterEach
    void tearDown() {
        GameRoom r = gameRoomRepository.findById(room.getId()).orElse(null);
        if (r != null) {
            chatRepository.deleteByGameRoom(r);
            gameRoomRepository.delete(r);  // participants cascade
        }
        memberRepository.delete(host);
        memberRepository.delete(guest);
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

    private GameRoomParticipant.ParticipantStatus statusOf(Member member) {
        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        return participantRepository.findByGameRoomAndMember(r, member).orElseThrow().getStatus();
    }

    private void waitPastGrace() throws InterruptedException {
        Thread.sleep(graceMs * 4);
    }

    /** 페이지를 열고 그 페이지에 심긴 언로드 토큰을 돌려준다. 템플릿에 실제로 렌더됐는지도 확인한다. */
    private String openPage(String path, Member member) throws Exception {
        MvcResult result = mockMvc.perform(get(path).with(user(new CustomUserDetails(member))))
                .andExpect(status().isOk())
                .andReturn();
        String token = (String) result.getModelAndView().getModel().get("unloadToken");
        assertThat(token).as("페이지 모델의 unloadToken").isNotBlank();
        assertThat(result.getResponse().getContentAsString()).as("템플릿에 렌더된 토큰").contains(token);
        return token;
    }

    private void beacon(Member member, String token) throws Exception {
        var request = post("/game/multi/room/" + room.getRoomCode() + "/unload")
                .with(user(new CustomUserDetails(member)));
        if (token != null) {
            request = request.param("token", token);
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private String waitingPath() {
        return "/game/multi/room/" + room.getRoomCode();
    }

    @Test
    @DisplayName("새로고침: 새 페이지 GET 뒤에 옛 페이지의 신호가 도착해도 방에 남는다")
    void staleBeaconAfterReload_isIgnored() throws Exception {
        String oldToken = openPage(waitingPath(), guest);
        openPage(waitingPath(), guest);   // 새로고침 — 새 페이지 GET 이 먼저 처리된다
        beacon(guest, oldToken);          // 옛 페이지의 pagehide 가 늦게 도착

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("게임 시작: 대기실 신호가 플레이 페이지 GET 뒤에 도착해도 게임에 남는다")
    void staleWaitingBeaconAfterGameStart_isIgnored() throws Exception {
        String guestWaiting = openPage(waitingPath(), guest);
        String hostWaiting = openPage(waitingPath(), host);
        mockMvc.perform(post(waitingPath() + "/ready").with(user(new CustomUserDetails(guest))).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post(waitingPath() + "/start").with(user(new CustomUserDetails(host))).with(csrf()))
                .andExpect(status().isOk());
        assertThat(gameRoomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(GameRoom.RoomStatus.PLAYING);

        openPage(waitingPath() + "/play", guest);
        openPage(waitingPath() + "/play", host);
        beacon(guest, guestWaiting);
        beacon(host, hostWaiting);

        waitPastGrace();

        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.PLAYING);
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.PLAYING);
    }

    @Test
    @DisplayName("탭 닫기: 최신 페이지의 신호이고 뒤이은 페이지 GET 이 없으면 유예 뒤 나간다")
    void latestBeacon_leavesAfterGrace() throws Exception {
        String token = openPage(waitingPath(), guest);
        beacon(guest, token);

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);  // 즉시 나가지 않음
        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("방장 탭 닫기: 유예 뒤 남은 참가자에게 방장이 넘어간다")
    void hostLatestBeacon_delegatesAfterGrace() throws Exception {
        String token = openPage(waitingPath(), host);
        beacon(host, token);

        waitPastGrace();

        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(r.getHost().getId()).isEqualTo(guest.getId());
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("최신 신호로 예약된 나가기도 유예 중 페이지를 다시 열면 취소된다")
    void pendingLeave_isCancelledByNextPageGet() throws Exception {
        String token = openPage(waitingPath(), guest);
        beacon(guest, token);             // beforeunload 처럼 새 페이지 GET 보다 먼저 온 경우
        openPage(waitingPath(), guest);

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("배포 전 페이지처럼 서버가 모르는 토큰은 무시한다")
    void unknownToken_isIgnored() throws Exception {
        openPage(waitingPath(), guest);
        beacon(guest, "issued-by-a-previous-process");

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("토큰 없는 신호는 무시한다 (옛 JS·외부 요청)")
    void missingToken_isIgnored() throws Exception {
        openPage(waitingPath(), guest);
        beacon(guest, null);

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("같은 계정의 탭 두 개: 최신 탭을 닫으면 나간다 (2026-09-21 허용 결정)")
    void twoTabs_closingLatestTabLeaves() throws Exception {
        String firstTab = openPage(waitingPath(), guest);
        String secondTab = openPage(waitingPath(), guest);
        beacon(guest, firstTab);          // 옛 탭 닫기 — 무시
        waitPastGrace();
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);

        beacon(guest, secondTab);         // 최신 탭 닫기 — 나간다
        waitPastGrace();
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }
}
