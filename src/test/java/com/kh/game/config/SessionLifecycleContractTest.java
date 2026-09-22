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
import org.springframework.test.context.TestPropertySource;
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
 * 세션 유휴 한도를 3초로 줄여 운영 60분 상황을 만든다. 세션 저장소를 바꿀 때도 이 계약이 유지돼야 한다 —
 * 같은 7건을 DB 세션 저장소(session-jdbc, H2)로도 돌린다: {@link SessionLifecycleJdbcContractTest}.
 *
 * 유휴 한도 3초를 주는 길이 저장소마다 다르다 (2026-09-22): 메모리(Tomcat)는 HttpSessionListener 로 초 단위를 직접 넣는다 —
 * server.servlet.session.timeout 은 Tomcat 에서 분 단위로 올림돼 1분이 된다. DB 저장소는 세션 이벤트가 없어 리스너가 안 불리므로
 * spring.session.timeout 으로 준다. 둘 다 두면 각 저장소에서 자기 쪽만 듣는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "server.servlet.session.timeout=" + SessionLifecycleContractTest.IDLE_LIMIT_SECONDS + "s",
        "spring.session.timeout=" + SessionLifecycleContractTest.IDLE_LIMIT_SECONDS + "s",
        // DB 저장소에서 세션이 끝난 WebSocket 을 닫는 검사 주기 (운영 30초). 계약 3번이 만료 후 2초 안에 닫힘을 본다
        "game.multi.ws-session-check-ms=300"
})
@DisplayName("로그인 세션 수명 계약")
class SessionLifecycleContractTest {

    private static final String PASSWORD = "Passw0rd!lifecycle";
    static final int IDLE_LIMIT_SECONDS = 3;
    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    /** 메모리 세션(Tomcat)용 유휴 한도 — DB 저장소에서는 불리지 않는다 (위 주석) */
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

        // 닫히는 시점은 저장소마다 다르다: 메모리(Tomcat)는 다음 HTTP 요청이 만료를 감지할 때, DB 저장소는 WebSocketHttpSessionGuard 가
        // 주기 검사에서 먼저 닫는다. 계약은 "만료되면 닫힌다" 뿐이라 만료 직후 열려 있음은 확인하지 않는다 (2026-09-22, 개발자 허가)
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

    @Test
    @DisplayName("로그인 페이지만 본 방문자(세션 쿠키는 있음)의 상태 확인은 NOT_LOGGED_IN 이다 — 세션이 있다 ≠ 로그인돼 있다")
    void anonymousWithSessionCookie_isNotLoggedIn() throws Exception {
        Browser browser = new Browser();
        browser.get("/auth/login");  // CSRF 토큰 때문에 익명 세션이 생긴다

        assertThat(browser.cookies.getCookieStore().getCookies()).as("익명 세션 쿠키").isNotEmpty();
        assertThat(browser.get("/auth/validate-session")).contains("\"valid\":false").contains("NOT_LOGGED_IN");
    }

    @Test
    @DisplayName("다른 탭에서 로그아웃하면(쿠키가 사라지고 폴링이 로그인 페이지를 받아 익명 세션이 생김) 열린 탭의 상태 확인은 NOT_LOGGED_IN 이다")
    void logoutInAnotherTab_isDetectedByOpenTab() throws Exception {
        Browser browser = new Browser().login();
        assertThat(browser.get("/auth/validate-session")).contains("\"valid\":true");

        browser.logout();
        browser.get("/auth/login");  // 열린 탭의 다음 요청이 로그인 페이지로 튕기며 익명 세션이 생기는 상황 (2026-09-22 운영 관찰)

        assertThat(browser.get("/auth/validate-session")).contains("\"valid\":false").contains("NOT_LOGGED_IN");
        assertThat(browser.get("/auth/status")).contains("\"isLoggedIn\":false");
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

        /** 다른 탭에서 누른 로그아웃 — 같은 쿠키 저장소이므로 세션 쿠키가 브라우저 전체에서 사라진다 */
        void logout() throws Exception {
            Matcher m = CSRF_META.matcher(get("/auth/login"));
            assertThat(m.find()).as("CSRF 메타 태그").isTrue();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/auth/security-logout"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as("로그아웃 응답").isEqualTo(302);
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
