package com.kh.game.support;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 테스트 메서드마다 애플리케이션 캐시를 전부 비운다.
 *
 * 스프링 테스트 컨텍스트는 suite 전체에서 재사용되지만 @Transactional 테스트의 DB 는 메서드마다 롤백된다.
 * 캐시만 남아 있으면 앞 테스트가 채운 곡 집계·장르 목록을 뒤 테스트가 보게 되므로,
 * 캐시를 끄는 대신(실제 동작을 그대로 두고) 메서드 시작 전에 비운다.
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class CacheClearingTestExecutionListener extends AbstractTestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        testContext.getApplicationContext().getBeansOfType(CacheManager.class).values()
                .forEach(manager -> manager.getCacheNames().forEach(name -> {
                    Cache cache = manager.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));
    }

    @Override
    public int getOrder() {
        // DependencyInjectionTestExecutionListener(2000)·DirtiesContext 뒤 — 컨텍스트가 준비된 다음에 비운다
        return Ordered.LOWEST_PRECEDENCE;
    }
}
