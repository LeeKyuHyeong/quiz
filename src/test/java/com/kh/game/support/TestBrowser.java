package com.kh.game.support;

import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
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

/**
 * 실제 서버(RANDOM_PORT)에 붙는 브라우저 한 개 — 쿠키 저장소를 따로 갖는 HTTP 클라이언트 + STOMP 연결.
 * 폼 로그인으로 세션을 만들고, 그 세션 쿠키로 WebSocket 핸드셰이크를 한다 (브라우저와 같은 경로).
 */
public class TestBrowser {

    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    private final int port;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private String csrf;

    public TestBrowser(int port) {
        this.port = port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public TestBrowser login(String email, String password) throws Exception {
        String form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrfFrom(get("/auth/login")), StandardCharsets.UTF_8);
        http.send(HttpRequest.newBuilder(uri("/auth/login-process"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        String status = get("/auth/status");
        if (!status.contains("\"isLoggedIn\":true")) {
            throw new IllegalStateException("login failed: " + status);
        }
        return this;
    }

    /** 페이지 GET — 응답의 CSRF 토큰을 이후 POST 에 쓴다 */
    public String open(String path) throws Exception {
        String body = get(path);
        Matcher m = CSRF_META.matcher(body);
        if (m.find()) {
            csrf = m.group(1);
        }
        return body;
    }

    public String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Accept", "*/*").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** fetch 래퍼처럼 CSRF 헤더를 실은 POST (먼저 open 으로 페이지를 열어 토큰을 받아 둔다) */
    public String post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("X-CSRF-TOKEN", csrf)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 브라우저처럼 CSRF 없이 폼 본문으로 보내는 sendBeacon */
    public void beacon(String path, String token) throws Exception {
        http.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** STOMP 연결. heartbeat 는 {클라이언트가 보낼 간격, 서버에게 바라는 간격} — 기본 {0,0}(하트비트 없음) */
    public StompSession connectStomp(long... heartbeat) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", cookies.getCookieStore().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; ")));
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.setHeartbeat(heartbeat.length == 2 ? heartbeat : new long[]{0, 0});
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        return client.connectAsync(URI.create("ws://localhost:" + port + "/ws/websocket"), handshakeHeaders,
                connectHeaders, new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
    }

    public StompSession subscribeRoom(String roomCode, long... heartbeat) throws Exception {
        StompSession session = connectStomp(heartbeat);
        session.subscribe("/topic/room/" + roomCode, new StompSessionHandlerAdapter() { });
        return session;
    }

    private static String csrfFrom(String html) {
        Matcher m = CSRF_META.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("no CSRF meta");
        }
        return m.group(1);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
