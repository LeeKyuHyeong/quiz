package com.kh.game.config;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 세션 수명 계약 (2026-09-18). 실제 Tomcat 에 HTTP·STOMP 로 붙어 확인한다.
 *
 * - 열린 탭의 주기적 상태 확인(/auth/validate-session, common.js 30초)은 세션을 연장하지 않는다.
 *   연장하면 탭을 열어 둔 동안 세션이 만료되지 않는다 (이전 동작).
 * - WebSocket 트래픽은 HTTP 세션을 연장하지 않는다. 멀티 화면은 WebSocket 이 붙으면 폴링을 멈추므로
 *   ws-client.js 가 연결 중 주기적으로 /auth/status 를 불러 세션을 유지한다.
 * - 다른 기기에서 로그인하면 먼저 있던 화면은 상태 확인과 fetch 응답으로 그 사실을 알 수 있다.
 *
 * 세션 유휴 한도를 3초로 줄여 운영 60분 상황을 만든다. 세션 저장소를 바꿀 때도 이 계약이 유지돼야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("로그인 세션 수명 계약")
class SessionLifecycleContractTest {

    private static final String PASSWORD = "Passw0rd!lifecycle";
    private static final int IDLE_LIMIT_SECONDS = 3;
    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    @TestConfiguration
    static class ShortSessionConfig {
        @Bean
        HttpSessionListener shortIdleLimit() {
            return new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent se) {
                    se.getSession().setMaxInactiveInterval(IDLE_LIMIT_SECONDS);
                }
            };
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberLoginHistoryRepository loginHistoryRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long memberId;
    private String email;

    @BeforeEach
    void createMember() {
        Member member = new Member();
        email = "lifecycle-" + System.nanoTime() + "@test.com";
        member.setEmail(email);
        member.setPassword(passwordEncoder.encode(PASSWORD));
        member.setNickname("life" + (System.nanoTime() % 1000000));
        member.setUsername("lifecycle");
        member.setRole(Member.MemberRole.USER);
        member.setStatus(Member.MemberStatus.ACTIVE);
        memberId = memberRepository.save(member).getId();
    }

    @AfterEach
    void cleanUp() {
        loginHistoryRepository.deleteAll(loginHistoryRepository
                .findByMemberIdOrderByCreatedAtDesc(memberId, Pageable.unpaged()).getContent());
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("아무 요청 없이 유휴 한도를 넘기면 로그아웃되고, 상태 확인은 NOT_LOGGED_IN 을 돌려준다")
    void idleSession_expires() throws Exception {
        Browser browser = new Browser().login();

        TimeUnit.SECONDS.sleep(IDLE_LIMIT_SECONDS + 2);

        assertThat(browser.get("/auth/validate-session")).contains("\"valid\":false").contains("NOT_LOGGED_IN");
        assertThat(browser.get("/auth/status")).contains("\"isLoggedIn\":false");
    }

    @Test
    @DisplayName("주기적 상태 확인(/auth/validate-session)은 세션을 연장하지 않는다")
    void periodicSessionCheck_doesNotExtendSession() throws Exception {
        Browser browser = new Browser().login();

        for (int i = 0; i < IDLE_LIMIT_SECONDS + 2; i++) {
            TimeUnit.SECONDS.sleep(1);
            String body = browser.get("/auth/validate-session");
            if (i == 0) {
                assertThat(body).as("유휴 한도 전").contains("\"valid\":true");
            }
        }

        assertThat(browser.get("/auth/status")).contains("\"isLoggedIn\":false");
    }

    @Test
    @DisplayName("WebSocket 연결만으로는 세션이 유지되지 않는다 - 만료되면 그 세션에 묶인 WebSocket 도 닫힌다")
    void webSocketAlone_doesNotKeepSessionAlive() throws Exception {
        Browser browser = new Browser().login();
        StompSession stomp = browser.connectStomp();

        TimeUnit.SECONDS.sleep(IDLE_LIMIT_SECONDS + 2);

        assertThat(stomp.isConnected()).as("만료가 감지되기 전까지 WebSocket 은 연결돼 있다").isTrue();
        assertThat(browser.get("/auth/status")).contains("\"isLoggedIn\":false");
        for (int i = 0; i < 20 && stomp.isConnected(); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(stomp.isConnected()).as("세션 만료 후 WebSocket").isFalse();
    }

    @Test
    @DisplayName("WebSocket 연결 중 주기적으로 /auth/status 를 부르면 (ws-client.js keepalive) 유휴 한도를 넘겨도 유지된다")
    void webSocketKeepAlive_keepsSessionAlive() throws Exception {
        Browser browser = new Browser().login();
        StompSession stomp = browser.connectStomp();

        for (int i = 0; i < IDLE_LIMIT_SECONDS + 2; i++) {
            TimeUnit.SECONDS.sleep(1);
            browser.get("/auth/status");
        }

        assertThat(browser.get("/auth/status")).contains("\"isLoggedIn\":true");
        assertThat(stomp.isConnected()).isTrue();
        stomp.disconnect();
    }

    @Test
    @DisplayName("다른 기기에서 로그인하면 먼저 있던 화면은 상태 확인과 화면 이동에서 그 사실을 안다")
    void secondLogin_isDetectedByFirstBrowser() throws Exception {
        Browser first = new Browser().login();
        new Browser().login();

        assertThat(first.get("/auth/validate-session"))
                .contains("\"valid\":false").contains("SESSION_INVALIDATED");

        HttpResponse<String> navigation = first.send("/mypage", "text/html,application/xhtml+xml");
        assertThat(navigation.statusCode()).isEqualTo(302);
        assertThat(navigation.headers().firstValue("Location").orElse("")).endsWith("/auth/login?expired=true");
    }

    @Test
    @DisplayName("만료 표시된 세션의 fetch 요청은 로그인 페이지 리다이렉트가 아니라 401 SESSION_INVALIDATED 를 받는다")
    void fetchWithInvalidatedSession_gets401Json() throws Exception {
        Browser first = new Browser().login();
        new Browser().login();

        HttpResponse<String> fetch = first.send("/auth/status", "*/*");

        assertThat(fetch.statusCode()).isEqualTo(401);
        assertThat(fetch.body()).contains("\"error\":\"SESSION_INVALIDATED\"");
    }

    @Test
    @DisplayName("로그인하지 않은 방문자의 상태 확인은 NOT_LOGGED_IN 이고 세션 쿠키를 만들지 않는다")
    void anonymousSessionCheck_createsNoSession() throws Exception {
        Browser browser = new Browser();

        assertThat(browser.get("/auth/validate-session")).contains("NOT_LOGGED_IN");
        assertThat(browser.cookies.getCookieStore().getCookies()).isEmpty();
    }

    /** 쿠키 저장소를 따로 갖는 HTTP 클라이언트 = 브라우저 한 개 */
    private class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        Browser login() throws Exception {
            Matcher m = CSRF_META.matcher(get("/auth/login"));
            assertThat(m.find()).as("로그인 페이지의 CSRF 메타 태그").isTrue();

            String form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                    + "&_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
            http.send(HttpRequest.newBuilder(uri("/auth/login-process"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(get("/auth/status")).as("로그인 직후").contains("\"isLoggedIn\":true");
            return this;
        }

        String get(String path) throws Exception {
            return send(path, "*/*").body();
        }

        HttpResponse<String> send(String path, String accept) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).header("Accept", accept).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        StompSession connectStomp() throws Exception {
            WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
            handshakeHeaders.add("Cookie", cookies.getCookieStore().getCookies().stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; ")));
            WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
            return client.connectAsync("ws://localhost:" + port + "/ws/websocket", handshakeHeaders,
                    new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
