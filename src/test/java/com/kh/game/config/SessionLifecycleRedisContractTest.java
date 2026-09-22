package com.kh.game.config;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link SessionLifecycleContractTest} 의 계약을 Redis 세션 저장소(Spring Session Data Redis, indexed)로 돌린다 — AWS 시연(ElastiCache)용
 * {@code session-redis} 프로파일. 부모의 "test" 에 "session-redis" 를 겹친다: 테스트 설정의 Session·Redis 자동구성 제외를 되돌리고,
 * SessionRegistry 는 저장소를 읽는 것으로, 세션이 끝난 WebSocket 은 WebSocketHttpSessionGuard 가 닫는다 (JDBC 와 같은 길).
 *
 * <p>실제 Redis 컨테이너(Testcontainers)로 돈다. Docker 가 없는 PC 에서는 이 클래스만 건너뛴다 — CI(ubuntu) 와 집 PC 에서 실행된다.
 * 프로파일 설정 그대로(configure-action=none = ElastiCache 처럼 CONFIG 명령 없이) 돌아, 키스페이스 이벤트 없이도
 * 만료(TTL)·principal 인덱스·1계정 1세션이 맞는지를 본다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("session-redis")
@DisplayName("로그인 세션 수명 계약 — Redis 세션 저장소(session-redis)")
class SessionLifecycleRedisContractTest extends SessionLifecycleContractTest {

    static final String REDIS_IMAGE = "redis:7-alpine";

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }
}
