package com.kh.game.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 제한이 X-Forwarded-For 의 첫 값을 클라이언트 IP 로 썼다 (2026-09-19 발견).
 * nginx 는 클라이언트가 보낸 헤더 뒤에 실제 접속 IP 를 덧붙이므로($proxy_add_x_forwarded_for),
 * 첫 값은 클라이언트가 마음대로 정할 수 있다 — 요청마다 값을 바꾸면 매번 새 버킷을 받아 제한이 걸리지 않았다.
 * 운영은 server.forward-headers-strategy=native 라 Tomcat 이 헤더를 오른쪽부터 읽어 실제 IP 를 getRemoteAddr() 에 넣어 준다.
 * 이 테스트는 운영과 같은 설정으로 실제 Tomcat 을 띄우고, nginx 가 덧붙인 모양의 헤더를 보낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
@ActiveProfiles("test")
@DisplayName("요청 제한의 클라이언트 IP — 위조한 X-Forwarded-For 로 우회할 수 없다")
class ClientIpSpoofingTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private int checkEmail(String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/auth/check-email?email=spoof@test.com"))
                .header("X-Forwarded-For", forwardedFor)
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("헤더 앞쪽 값을 요청마다 바꿔도 실제 접속 IP 기준으로 21번째 요청이 429 다")
    void spoofedForwardedFor_doesNotGetAFreshBucket() throws Exception {
        String realIp = "203.0.113.7";
        for (int i = 0; i < 20; i++) {
            assertThat(checkEmail("198.51.100." + i + ", " + realIp)).isEqualTo(200);
        }
        assertThat(checkEmail("198.51.100.99, " + realIp)).isEqualTo(429);
    }

    @Test
    @DisplayName("위조하지 않은 일반 요청은 nginx 가 넣은 IP 로 제한된다")
    void plainRequest_isLimitedByProxyProvidedIp() throws Exception {
        String realIp = "203.0.113.8";
        for (int i = 0; i < 20; i++) {
            assertThat(checkEmail(realIp)).isEqualTo(200);
        }
        assertThat(checkEmail(realIp)).isEqualTo(429);
        assertThat(checkEmail("203.0.113.9")).as("다른 IP 는 영향받지 않는다").isEqualTo(200);
    }
}
