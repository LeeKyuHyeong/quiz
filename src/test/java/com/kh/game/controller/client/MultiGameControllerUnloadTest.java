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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 페이지 언로드(탭 닫기·뒤로가기) 시 나가기.
 *
 * 배경: 대기실·플레이 화면은 navigator.sendBeacon 으로 /leave 를 보냈는데, sendBeacon 은 CSRF 헤더를
 * 실을 수 없어 CsrfFilter 가 403 으로 막았다. 탭을 닫은 방장의 방이 로비에 남고 위임도 일어나지 않았다
 * (2026-09-16 발견). 새 /unload 는 CSRF 예외이며 즉시 나가지 않고 유예 뒤에 적용한다 — 새로고침이나
 * 대기실→플레이 같은 게임 내 이동은 다음 페이지 GET 이 유예 중인 나가기를 취소한다.
 * 2026-09-21: 신호는 페이지 토큰을 실어야 받는다(최신 페이지만) — 아래 테스트는 대기실을 열어 받은 토큰으로 보낸다.
 * 토큰·순서 규칙 자체는 MultiGameControllerUnloadTokenTest.
 *
 * 지연 처리가 스케줄러 스레드에서 별도 트랜잭션으로 돌므로 이 테스트는 @Transactional 을 쓰지 않고 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MultiGameController - 언로드 나가기")
class MultiGameControllerUnloadTest {

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
        host = createMember("unload_host");
        guest = createMember("unload_guest");
        room = gameRoomService.createRoom(host, "unload room", 4, 5, false, "{}");
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

    /** 대기실을 열고 그 페이지의 언로드 토큰을 돌려준다 (브라우저의 pagehide 신호가 싣는 값). */
    private String openWaitingRoom(Member member) throws Exception {
        return (String) mockMvc.perform(get("/game/multi/room/" + room.getRoomCode())
                        .with(user(new CustomUserDetails(member))))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("unloadToken");
    }

    @Test
    @DisplayName("sendBeacon 처럼 CSRF 토큰 없이 보내도 200 이고, 즉시 나가지는 않는다")
    void unload_withoutCsrf_isAcceptedAndDeferred() throws Exception {
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/unload")
                        .with(user(new CustomUserDetails(guest))))
                .andExpect(status().isOk());

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("유예 시간이 지나면 나가기가 적용된다")
    void unload_appliesLeaveAfterGrace() throws Exception {
        String token = openWaitingRoom(guest);
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/unload")
                        .param("token", token)
                        .with(user(new CustomUserDetails(guest))))
                .andExpect(status().isOk());

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("유예 중에 대기실 페이지를 다시 열면(새로고침) 나가기가 취소된다")
    void unload_isCancelledByReopeningWaitingRoom() throws Exception {
        String token = openWaitingRoom(guest);
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/unload")
                        .param("token", token)
                        .with(user(new CustomUserDetails(guest))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/game/multi/room/" + room.getRoomCode())
                        .with(user(new CustomUserDetails(guest))))
                .andExpect(status().isOk());

        waitPastGrace();

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("방장이 탭을 닫으면 유예 뒤 남은 참가자에게 방장이 넘어간다")
    void hostUnload_delegatesAfterGrace() throws Exception {
        String token = openWaitingRoom(host);
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/unload")
                        .param("token", token)
                        .with(user(new CustomUserDetails(host))))
                .andExpect(status().isOk());

        waitPastGrace();

        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(r.getHost().getId()).isEqualTo(guest.getId());
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("기존 /leave 는 여전히 CSRF 토큰이 필요하다 (예외는 /unload 하나뿐)")
    void leave_withoutCsrf_isStillRejected() throws Exception {
        mockMvc.perform(post("/game/multi/room/" + room.getRoomCode() + "/leave")
                        .with(user(new CustomUserDetails(guest))))
                .andExpect(status().isForbidden());

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }
}
