package com.kh.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * 로그인 세션 저장소에 맞는 {@link SessionRegistry} (1계정 1세션 · 관리자 강제 종료 · /auth/validate-session 이 쓴다).
 *
 * <p>{@code session-jdbc} 프로파일(prod 에 포함)·{@code session-redis}(AWS 시연)에서는 세션이 저장소에 있으므로 레지스트리도 저장소를 그대로 읽어야 한다.
 * 메모리 레지스트리를 그대로 두면 프로세스가 바뀐 뒤 세션은 DB 에 살아 있는데 레지스트리는 비어 있어,
 * 상태 확인이 NOT_LOGGED_IN 을 돌려주고 열린 탭이 전부 로그인 화면으로 간다 (O-020 과 같은 증상).
 *
 * <p>메모리 세션(dev·test)에서는 세션이 시간 초과로 사라질 때 레지스트리에서도 지워야 하므로 {@link HttpSessionEventPublisher} 가 필요하다.
 * 없으면 죽은 세션이 쌓이고 상태 확인이 끝난 세션을 유효하다고 답한다.
 */
@Configuration
public class SessionStoreConfig {

    @Configuration
    @Profile("!session-jdbc & !session-redis")
    static class InMemory {
        @Bean
        SessionRegistry sessionRegistry() {
            return new SessionRegistryImpl();
        }

        @Bean
        HttpSessionEventPublisher httpSessionEventPublisher() {
            return new HttpSessionEventPublisher();
        }
    }

    @Configuration
    @Profile({"session-jdbc", "session-redis"})
    static class Store {
        @Bean
        <S extends Session> SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<S> sessionRepository) {
            return new SpringSessionBackedSessionRegistry<>(sessionRepository);
        }
    }
}
