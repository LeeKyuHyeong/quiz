package com.kh.game.config;

import com.kh.game.security.WebSocketHttpSessionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 저장소 프로파일이 서로 새지 않는다 (2026-09-22, session-redis 추가).
 *
 * <p>Redis 클라이언트가 클래스패스에 있으면 Boot 는 프로파일과 상관없이 RedisConnectionFactory 를 만들고, 헬스 지표가
 * localhost:6379 에 붙으려다 /actuator/health 를 DOWN 으로 만든다 — VPS(prod = session-jdbc)의 배포 게이트가 막힌다.
 * 그래서 Redis 자동구성은 기본에서 끄고 session-redis 프로파일만 되돌린다. 이 테스트가 그 설정 사슬을 고정한다.
 */
@DisplayName("세션 저장소 프로파일 격리")
class SessionStoreProfileTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("메모리 세션(test) — Redis 연결도 세션 저장소도 없다")
    class InMemory {
        @Autowired
        ApplicationContext context;

        @Test
        void noRedisNoSessionRepository() {
            assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SessionRepository.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WebSocketHttpSessionGuard.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "session-jdbc"})
    @DisplayName("DB 세션 저장소(session-jdbc = prod) — Redis 연결이 생기지 않는다")
    class Jdbc {
        @Autowired
        ApplicationContext context;

        @Test
        void jdbcRepositoryWithoutRedis() {
            assertThat(context.getBean(SessionRepository.class)).isInstanceOf(JdbcIndexedSessionRepository.class);
            assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WebSocketHttpSessionGuard.class)).hasSize(1);
        }
    }

    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"test", "session-redis"})
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("Redis 세션 저장소(session-redis = AWS 시연)")
    class Redis {
        @Container
        static final GenericContainer<?> redis =
                new GenericContainer<>(SessionLifecycleRedisContractTest.REDIS_IMAGE).withExposedPorts(6379);

        @DynamicPropertySource
        static void redisProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        }

        @Autowired
        ApplicationContext context;
        @LocalServerPort
        int port;

        @Test
        @Order(1)
        @DisplayName("indexed Redis 저장소(principal 인덱스 = 1계정 1세션·강제 종료에 필요)와 WebSocket 가드가 있다")
        void indexedRedisRepositoryWithGuard() {
            assertThat(context.getBean(SessionRepository.class)).isInstanceOf(RedisIndexedSessionRepository.class);
            assertThat(context.getBeanNamesForType(WebSocketHttpSessionGuard.class)).hasSize(1);
        }

        @Test
        @Order(2)
        @DisplayName("Redis 가 살아 있으면 헬스 UP, 죽으면 UP 이 아니다 (폴백 없음 — 배포 게이트·로드밸런서 헬스 체크가 막는다)")
        void healthFollowsRedis() throws Exception {
            HttpResponse<String> alive = health();
            assertThat(alive.statusCode()).isEqualTo(200);
            assertThat(alive.body()).contains("\"status\":\"UP\"");

            redis.stop();

            // 실측(2026-09-22): 503 DOWN 이 아니라 500 — 헬스 엔드포인트에 닿기 전에 SessionRepositoryFilter 가 이 요청의 세션을
            // 저장하려다(익명 요청에도 세션이 생김) Redis 타임아웃(2초)으로 죽는다. JDBC 저장소도 DB 가 죽으면 같은 모양.
            // 계약은 "UP 을 돌려주지 않는다" 까지 — 게이트는 200 만 통과시킨다 (open-issues O-024)
            HttpResponse<String> dead = health();
            assertThat(dead.statusCode()).as("Redis 정지 뒤 헬스 HTTP 상태").isNotEqualTo(200);
            assertThat(dead.body()).doesNotContain("\"status\":\"UP\"");
        }

        private HttpResponse<String> health() throws Exception {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
