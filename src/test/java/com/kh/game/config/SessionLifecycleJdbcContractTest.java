package com.kh.game.config;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link SessionLifecycleContractTest} 의 7건을 DB 세션 저장소(Spring Session JDBC, 테스트는 H2 자동 스키마)로 돌린다.
 * 부모의 "test" 에 "session-jdbc" 를 겹친다 — 테스트 설정의 SessionAutoConfiguration 제외를 되돌리고,
 * SessionRegistry 는 저장소를 읽는 것으로, 세션이 끝난 WebSocket 은 WebSocketHttpSessionGuard 가 닫는다.
 *
 * 운영(prod)은 이 프로파일이 항상 포함된다. 메모리 세션 전용 동작(Tomcat 이 WebSocket 을 닫음, HttpSessionEventPublisher)에
 * 기대는 회귀를 여기서 잡는다.
 */
@ActiveProfiles("session-jdbc")
@DisplayName("로그인 세션 수명 계약 — DB 세션 저장소(session-jdbc)")
class SessionLifecycleJdbcContractTest extends SessionLifecycleContractTest {
}
