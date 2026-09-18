package com.kh.game.config;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.Valve;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 내장 Tomcat 의 인증 밸브(NonLoginAuthenticator)는 기본값(cache=true)에서 세션 쿠키가 달린 모든 요청마다
 * 캐시된 Principal 을 찾으려고 세션을 읽는다 (AuthenticatorBase.invoke → getSessionInternal(false)).
 * 그러면 애플리케이션이 세션을 건드리지 않는 요청(/auth/validate-session)도 유휴 시간을 연장해,
 * 탭을 열어 둔 동안 세션이 만료되지 않는다 (SessionLifecycleContractTest).
 *
 * 컨테이너 인증은 쓰지 않으므로(Spring Security 가 인증) 캐시를 끈다.
 */
@Configuration
public class TomcatSessionAccessConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> disableAuthenticatorSessionCache() {
        // 인증 밸브는 컨텍스트 시작 중에 추가되므로 시작이 끝난 뒤에 찾는다
        return factory -> factory.addContextCustomizers(context -> context.addLifecycleListener(event -> {
            if (Lifecycle.AFTER_START_EVENT.equals(event.getType())) {
                for (Valve valve : context.getPipeline().getValves()) {
                    if (valve instanceof AuthenticatorBase authenticator) {
                        authenticator.setCache(false);
                    }
                }
            }
        }));
    }
}
