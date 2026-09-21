package com.kh.game.service;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.support.TestBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP 하트비트 — 네트워크가 조용히 끊긴 연결(TCP 종료 없이 클라이언트가 멈춤)도 서버가 끊어 접속 상태에서 뺀다.
 *
 * 운영값은 {서버 10초, 클라이언트 30초} → 서버는 클라이언트에게서 max(클라이언트 제안, 30초) × 3 동안 아무것도 못 받으면 끊는다
 * (stomp.js 기본 제안 10초 → 90초). 여기서는 비율을 유지한 채 줄인 값으로 계약을 확인하고 걸린 시간을 잰다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "game.multi.ws-heartbeat-server-ms=100",
        "game.multi.ws-heartbeat-client-ms=300"
})
@DisplayName("접속 상태(presence) - STOMP 하트비트")
class RoomPresenceHeartbeatTest {

    private static final String PASSWORD = "Passw0rd!heartbeat";
    private static final long CLIENT_MS = 300;

    @LocalServerPort
    private int port;

    @Autowired private GameRoomService gameRoomService;
    @Autowired private RoomPresenceService presence;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberLoginHistoryRepository loginHistoryRepository;
    @Autowired private GameRoomRepository gameRoomRepository;
    @Autowired private GameRoomParticipantRepository participantRepository;
    @Autowired private GameRoomChatRepository chatRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member host;
    private Member guest;
    private GameRoom room;
    private final List<Member> members = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private ThreadPoolTaskScheduler clientScheduler;

    @BeforeEach
    void setUp() {
        host = createMember("hh");
        guest = createMember("hg");
        room = gameRoomService.createRoom(host, "heartbeat room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        if (clientScheduler != null) {
            clientScheduler.shutdown();
        }
        TimeUnit.SECONDS.sleep(1);
        GameRoom r = gameRoomRepository.findById(room.getId()).orElse(null);
        if (r != null) {
            chatRepository.deleteByGameRoom(r);
            gameRoomRepository.delete(r);
        }
        for (Member m : members) {
            loginHistoryRepository.deleteAll(loginHistoryRepository
                    .findByMemberIdOrderByCreatedAtDesc(m.getId(), Pageable.unpaged()).getContent());
            memberRepository.deleteById(m.getId());
        }
    }

    private Member createMember(String prefix) {
        Member m = new Member();
        String name = prefix + (System.nanoTime() % 100000000);
        m.setUsername(name);
        m.setNickname(name);
        m.setEmail(name + "@test.com");
        m.setPassword(passwordEncoder.encode(PASSWORD));
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

    private void awaitConnected(Member member) throws InterruptedException {
        for (int i = 0; i < 60 && !presence.isConnected(room.getRoomCode(), member.getId()); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(presence.isConnected(room.getRoomCode(), member.getId())).isTrue();
    }

    @Test
    @DisplayName("하트비트를 약속하고 멈춘 클라이언트는 서버가 끊고, 유예 뒤 방에서 나간다 (걸린 시간 기록)")
    void silentClient_isDisconnectedAndLeaves() throws Exception {
        TestBrowser browser = new TestBrowser(port).login(guest.getEmail(), PASSWORD);
        // 하트비트를 100ms 마다 보내겠다고 약속하고 구독한 뒤 아무것도 안 보낸다(TCP 는 열린 채) = 조용히 끊긴 네트워크·멈춘 탭
        WebSocketSession ws = browser.silentStomp(room.getRoomCode(), 100);
        awaitConnected(guest);
        long start = System.currentTimeMillis();

        for (int i = 0; i < 100 && presence.isConnected(room.getRoomCode(), guest.getId()); i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        long detectedMs = System.currentTimeMillis() - start;
        System.out.println("[heartbeat] silent client detected after " + detectedMs + "ms (limit = 3 x " + CLIENT_MS + "ms)");

        assertThat(presence.isConnected(room.getRoomCode(), guest.getId())).as("서버가 끊음").isFalse();
        for (int i = 0; i < 20 && ws.isOpen(); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(ws.isOpen()).as("서버가 WebSocket 을 닫음").isFalse();
        assertThat(detectedMs).as("한도(3 × 클라이언트 간격) 근처").isBetween(3 * CLIENT_MS - 100, 3 * CLIENT_MS + 700);
        for (int i = 0; i < 60 && statusOf(guest) != GameRoomParticipant.ParticipantStatus.LEFT; i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("하트비트를 보내는 클라이언트는 한도를 여러 번 넘겨도 연결이 유지된다")
    void beatingClient_staysConnected() throws Exception {
        TestBrowser browser = new TestBrowser(port).login(guest.getEmail(), PASSWORD);
        clientScheduler = new ThreadPoolTaskScheduler();
        clientScheduler.initialize();
        StompSession s = browser.connectStomp(clientScheduler, 100, 100);
        s.subscribe("/topic/room/" + room.getRoomCode(), new StompSessionHandlerAdapter() { });
        sessions.add(s);
        awaitConnected(guest);

        TimeUnit.MILLISECONDS.sleep(CLIENT_MS * 3 * 3);

        assertThat(s.isConnected()).isTrue();
        assertThat(presence.isConnected(room.getRoomCode(), guest.getId())).isTrue();
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("하트비트를 약속하지 않은 클라이언트({0,0})는 서버가 검사하지 않는다")
    void noHeartbeatNegotiated_isNotChecked() throws Exception {
        TestBrowser browser = new TestBrowser(port).login(guest.getEmail(), PASSWORD);
        StompSession s = browser.subscribeRoom(room.getRoomCode());
        sessions.add(s);
        awaitConnected(guest);

        TimeUnit.MILLISECONDS.sleep(CLIENT_MS * 3 * 3);

        assertThat(s.isConnected()).isTrue();
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }
}
