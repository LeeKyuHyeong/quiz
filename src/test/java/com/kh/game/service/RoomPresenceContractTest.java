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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접속 상태(presence) 계약 — 브라우저가 나가기 신호를 못 보내도(창 전체 닫기, 2026-09-21 운영 실패 항목)
 * 방 토픽 WebSocket 연결이 모두 끊기면 유예 뒤 나간다. 실제 Tomcat 에 HTTP 로그인·STOMP 로 붙는다.
 *
 * 테스트 프로필의 끊김 유예(game.multi.disconnect-grace-ms)는 짧다. 지연 처리가 스케줄러 스레드에서
 * 별도 트랜잭션으로 돌므로 @Transactional 을 쓰지 않고 직접 정리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("접속 상태(presence) - 연결이 모두 끊기면 유예 뒤 나간다")
class RoomPresenceContractTest {

    private static final String PASSWORD = "Passw0rd!presence";

    @LocalServerPort
    private int port;
    @Value("${game.multi.disconnect-grace-ms}")
    private long graceMs;

    @Autowired private GameRoomService gameRoomService;
    @Autowired private RoomPresenceService presence;
    @Autowired private RoomUnloadService roomUnloadService;
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

    @BeforeEach
    void setUp() {
        host = createMember("ph");
        guest = createMember("pg");
        room = gameRoomService.createRoom(host, "presence room", 4, 5, false, "{}");
        gameRoomService.joinRoom(room.getRoomCode(), guest);
    }

    @AfterEach
    void tearDown() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        sleep(graceMs * 3);  // 정리 중 끊김으로 예약된 나가기가 방을 지우기 전에 끝나게
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

    private TestBrowser browserOf(Member member) throws Exception {
        return new TestBrowser(port).login(member.getEmail(), PASSWORD);
    }

    /** 방 토픽을 구독하고, 서버가 그 구독을 셀 때까지 기다린다 */
    private StompSession subscribe(TestBrowser browser, Member member) throws Exception {
        StompSession session = browser.subscribeRoom(room.getRoomCode());
        sessions.add(session);
        assertThat(waitUntil(() -> presence.isConnected(room.getRoomCode(), member.getId()), 3000))
                .as("구독이 접속 상태에 반영됨").isTrue();
        return session;
    }

    private GameRoomParticipant.ParticipantStatus statusOf(Member member) {
        GameRoom r = gameRoomRepository.findById(room.getId()).orElseThrow();
        return participantRepository.findByGameRoomAndMember(r, member).orElseThrow().getStatus();
    }

    private GameRoom reloadRoom() {
        return gameRoomRepository.findById(room.getId()).orElseThrow();
    }

    private boolean leftWithin(Member member, long millis) {
        return waitUntil(() -> statusOf(member) == GameRoomParticipant.ParticipantStatus.LEFT, millis);
    }

    private static boolean waitUntil(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("창 전체 닫기: 구독한 연결이 끊기고 아무 신호도 없으면 유예 뒤 나간다")
    void disconnectWithoutSignal_leavesAfterGrace() throws Exception {
        StompSession s = subscribe(browserOf(guest), guest);

        s.disconnect();

        assertThat(statusOf(guest)).as("즉시 나가지 않음").isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
        assertThat(leftWithin(guest, graceMs * 10)).as("유예 뒤 LEFT").isTrue();
    }

    @Test
    @DisplayName("방장 창 닫기: 유예 뒤 남은 참가자에게 방장이 넘어간다")
    void hostDisconnect_delegatesHost() throws Exception {
        StompSession s = subscribe(browserOf(host), host);

        s.disconnect();

        assertThat(leftWithin(host, graceMs * 10)).isTrue();
        assertThat(reloadRoom().getHost().getId()).isEqualTo(guest.getId());
    }

    @Test
    @DisplayName("새로고침: 끊긴 뒤 유예 안에 새 연결이 구독하면 남는다")
    void resubscribeWithinGrace_staysInRoom() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession old = subscribe(browser, guest);

        old.disconnect();
        waitUntil(() -> !presence.isConnected(room.getRoomCode(), guest.getId()), 1000);
        subscribe(browser, guest);

        sleep(graceMs * 4);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("순서 뒤집힘: 새 구독이 옛 연결 끊김보다 먼저 와도 남는다")
    void newSubscribeBeforeOldDisconnect_staysInRoom() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession old = subscribe(browser, guest);
        subscribe(browser, guest);

        old.disconnect();

        sleep(graceMs * 4);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
        assertThat(presence.isConnected(room.getRoomCode(), guest.getId())).isTrue();
    }

    @Test
    @DisplayName("탭 두 개: 하나만 닫으면 남고, 둘 다 닫으면 나간다")
    void twoConnections_leaveOnlyWhenBothClose() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession first = subscribe(browser, guest);
        StompSession second = subscribe(browser, guest);

        first.disconnect();
        sleep(graceMs * 4);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);

        second.disconnect();
        assertThat(leftWithin(guest, graceMs * 10)).isTrue();
    }

    @Test
    @DisplayName("게임 중 방장 연결 끊김: 유예 뒤 위임되고 게임은 계속된다")
    void hostDisconnectDuringGame_delegatesAndKeepsPlaying() throws Exception {
        TestBrowser hostBrowser = browserOf(host);
        TestBrowser guestBrowser = browserOf(guest);
        String waiting = "/game/multi/room/" + room.getRoomCode();
        guestBrowser.open(waiting);
        guestBrowser.post(waiting + "/ready");
        hostBrowser.open(waiting);
        assertThat(hostBrowser.post(waiting + "/start")).contains("\"success\":true");
        StompSession hostSession = subscribe(hostBrowser, host);
        subscribe(guestBrowser, guest);

        hostSession.disconnect();

        assertThat(leftWithin(host, graceMs * 10)).isTrue();
        GameRoom r = reloadRoom();
        assertThat(r.getHost().getId()).isEqualTo(guest.getId());
        assertThat(r.getStatus()).isEqualTo(GameRoom.RoomStatus.PLAYING);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.PLAYING);
    }

    @Test
    @DisplayName("유예 중 페이지를 다시 열면(대기실 GET) 취소된다")
    void pageGetWithinGrace_cancels() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession s = subscribe(browser, guest);

        s.disconnect();
        waitUntil(() -> !presence.isConnected(room.getRoomCode(), guest.getId()), 1000);
        browser.open("/game/multi/room/" + room.getRoomCode());

        sleep(graceMs * 4);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("참가자가 아닌 사용자의 구독은 거부되고 접속 상태에 들어가지 않는다")
    void deniedSubscribe_isNotTracked() throws Exception {
        Member outsider = createMember("po");
        StompSession s = browserOf(outsider).subscribeRoom(room.getRoomCode());
        sessions.add(s);

        waitUntil(() -> !s.isConnected(), 2000);
        assertThat(s.isConnected()).as("거부되면 서버가 연결을 닫는다").isFalse();
        assertThat(presence.isConnected(room.getRoomCode(), outsider.getId())).isFalse();
    }

    @Test
    @DisplayName("강퇴된 뒤 연결이 끊겨도 해가 없다 (방장·방 상태 그대로)")
    void disconnectAfterKick_isHarmless() throws Exception {
        TestBrowser hostBrowser = browserOf(host);
        StompSession guestSession = subscribe(browserOf(guest), guest);
        hostBrowser.open("/game/multi/room/" + room.getRoomCode());
        assertThat(hostBrowser.post("/game/multi/room/" + room.getRoomCode() + "/kick/" + guest.getId()))
                .contains("\"success\":true");

        guestSession.disconnect();
        sleep(graceMs * 4);

        GameRoom r = reloadRoom();
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.LEFT);
        assertThat(r.getHost().getId()).isEqualTo(host.getId());
        assertThat(r.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("종료 중인 프로세스의 끊김은 무시한다 (배포 때 옛 인스턴스가 모두를 내보내지 않게)")
    void disconnectWhileShuttingDown_isIgnored() {
        RoomPresenceService closing = new RoomPresenceService(roomUnloadService, 50);
        closing.subscribed("s-closing", room.getRoomCode(), guest.getId());
        closing.onContextClosed(null);

        closing.onDisconnect(new org.springframework.web.socket.messaging.SessionDisconnectEvent(
                this, org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0]).build(),
                "s-closing", org.springframework.web.socket.CloseStatus.GOING_AWAY));

        sleep(400);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    // ===== 결과 화면·재시작 (2026-09-22 권장안 2 레드팀) =====

    /** 대기실 → 준비 → 시작 → (게임 종료 대신) 방을 FINISHED 로. 참가자는 finishGame 처럼 PLAYING 그대로다. */
    private void playAndFinish(TestBrowser hostBrowser, TestBrowser guestBrowser) throws Exception {
        String waiting = "/game/multi/room/" + room.getRoomCode();
        guestBrowser.open(waiting);
        guestBrowser.post(waiting + "/ready");
        hostBrowser.open(waiting);
        assertThat(hostBrowser.post(waiting + "/start")).contains("\"success\":true");
        GameRoom r = reloadRoom();
        r.setStatus(GameRoom.RoomStatus.FINISHED);
        gameRoomRepository.save(r);
    }

    private long restartFloorMs() {
        return GameRoomService.LEAVE_IGNORED_AFTER_RESTART.toMillis() + 1000;
    }

    @Test
    @DisplayName("결과 화면에서 창을 닫은 참가자는 재시작한 대기실에 되살아나지 않는다")
    void closedOnResultScreen_doesNotReviveOnRestart() throws Exception {
        TestBrowser hostBrowser = browserOf(host);
        TestBrowser guestBrowser = browserOf(guest);
        playAndFinish(hostBrowser, guestBrowser);
        subscribe(hostBrowser, host);                        // 결과 화면 방장
        StompSession guestResult = subscribe(guestBrowser, guest);

        guestResult.disconnect();                            // 결과 화면에서 창 닫기
        sleep(graceMs * 4);                                  // 유예가 지나도 종료된 방이라 무시된다
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.PLAYING);

        hostBrowser.open("/game/multi/room/" + room.getRoomCode() + "/result");
        assertThat(hostBrowser.post("/game/multi/room/" + room.getRoomCode() + "/restart")).contains("\"success\":true");
        assertThat(statusOf(guest)).as("재시작 직후 되살아남").isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);

        assertThat(leftWithin(guest, restartFloorMs() + graceMs * 10)).as("다시 잡은 나가기로 LEFT").isTrue();
        GameRoom r = reloadRoom();
        assertThat(r.getHost().getId()).isEqualTo(host.getId());
        assertThat(r.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
    }

    @Test
    @DisplayName("결과 화면 방장: 결과 페이지 GET 뒤에 플레이 연결이 끊겨도, 결과 화면에서 구독하면 재시작 후 방장이 남는다")
    void hostSubscribedOnResult_staysHostAfterRestart() throws Exception {
        TestBrowser hostBrowser = browserOf(host);
        TestBrowser guestBrowser = browserOf(guest);
        String base = "/game/multi/room/" + room.getRoomCode();
        guestBrowser.open(base);
        guestBrowser.post(base + "/ready");
        hostBrowser.open(base);
        hostBrowser.post(base + "/start");
        StompSession hostPlay = subscribe(hostBrowser, host);
        subscribe(guestBrowser, guest);
        GameRoom r = reloadRoom();
        r.setStatus(GameRoom.RoomStatus.FINISHED);
        gameRoomRepository.save(r);

        hostBrowser.open(base + "/result");                  // 브라우저는 새 페이지를 받은 뒤에 옛 페이지를 내린다
        hostPlay.disconnect();                               // 그래서 플레이 연결 끊김이 결과 GET 보다 늦다
        subscribe(hostBrowser, host);                        // 결과 화면 방장 구독 (multi-result.js)
        assertThat(hostBrowser.post(base + "/restart")).contains("\"success\":true");

        // 운영 유예(60초)는 재시작 직후 무시 구간(5초)보다 길다 — 방장 나가기가 예약돼 있으면 재시작한 방에서 방장이 나간다.
        // 테스트 유예는 짧아 무시 구간에 가려지므로, 예약 자체가 없는지로 확인한다.
        assertThat(roomUnloadService.isLeavePending(room.getRoomCode(), host.getId())).as("방장 나가기 예약").isFalse();

        sleep(restartFloorMs() + graceMs * 4);
        r = reloadRoom();
        assertThat(r.getHost().getId()).isEqualTo(host.getId());
        assertThat(statusOf(host)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    // ===== 폴링으로 넘어간 사용자 (2026-09-22 권장안 4 레드팀) =====

    /** ws-client.js 는 재연결 5회 실패 뒤 폴링으로 넘어간다 — 연결은 없지만 화면은 살아 있는 사용자 */
    private void pollFor(TestBrowser browser, String path, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            browser.get(path);
            sleep(graceMs / 3);
        }
    }

    @Test
    @DisplayName("구독하던 사용자가 폴링으로 넘어가면(대기실 /status) 연결 끊김 나가기가 취소돼 남는다")
    void fallbackToStatusPolling_staysInRoom() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession s = subscribe(browser, guest);

        s.disconnect();
        pollFor(browser, "/game/multi/room/" + room.getRoomCode() + "/status", graceMs * 5);

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("폴링 /chats·/round 도 연결 끊김 나가기를 취소한다")
    void fallbackToChatAndRoundPolling_staysInRoom() throws Exception {
        TestBrowser browser = browserOf(guest);
        StompSession s = subscribe(browser, guest);
        String base = "/game/multi/room/" + room.getRoomCode();

        s.disconnect();
        long deadline = System.currentTimeMillis() + graceMs * 5;
        while (System.currentTimeMillis() < deadline) {
            browser.get(base + "/chats?lastId=0");
            browser.get(base + "/round");
            sleep(graceMs / 3);
        }

        assertThat(statusOf(guest)).isEqualTo(GameRoomParticipant.ParticipantStatus.JOINED);
    }

    @Test
    @DisplayName("폴링은 탭 닫기 신호로 잡힌 나가기는 취소하지 않는다 (다른 탭의 폴링이 닫은 탭의 나가기를 막지 않게)")
    void polling_doesNotCancelUnloadSignalLeave() throws Exception {
        TestBrowser browser = browserOf(guest);
        String base = "/game/multi/room/" + room.getRoomCode();
        String page = browser.open(base);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("const unloadToken = \"([^\"]+)\"").matcher(page);
        assertThat(m.find()).as("대기실 페이지의 unloadToken").isTrue();

        browser.beacon(base + "/unload", m.group(1));
        browser.get(base + "/status");

        assertThat(leftWithin(guest, 3000)).isTrue();
    }
}
