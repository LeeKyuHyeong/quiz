package com.kh.game.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP 별 버킷은 한 번 만들어지면 지워지지 않았다 (2026-09-19 발견) — 접속한 IP 수만큼 메모리가 계속 늘었다.
 */
@DisplayName("요청 제한 버킷 정리 — 10분 넘게 쓰이지 않은 IP 의 버킷은 지운다")
class LoginRateLimiterTest {

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    @Test
    @DisplayName("10분 넘게 쓰이지 않은 버킷만 지워진다")
    void idleBuckets_areEvicted() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        long t0 = System.nanoTime();
        limiter.tryAcquire("203.0.113.50", t0);
        limiter.tryAcquire("203.0.113.51", t0 + 5 * MINUTE);
        assertThat(limiter.bucketCount()).isEqualTo(2);

        limiter.tryAcquire("203.0.113.52", t0 + 11 * MINUTE);

        assertThat(limiter.bucketCount()).as("50 은 11분 전, 51 은 6분 전에 마지막으로 쓰였다").isEqualTo(2);
    }

    @Test
    @DisplayName("지워진 뒤 같은 IP 가 다시 오면 새 버킷으로 정상 동작한다")
    void evictedIp_startsFresh() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("203.0.113.53", t0)).isTrue();
        }
        assertThat(limiter.tryAcquire("203.0.113.53", t0)).isFalse();

        limiter.tryAcquire("203.0.113.54", t0 + 11 * MINUTE);

        assertThat(limiter.tryAcquire("203.0.113.53", t0 + 11 * MINUTE)).isTrue();
    }

    @Test
    @DisplayName("계속 쓰이는 버킷은 지워지지 않는다 — 한도에 걸린 IP 가 정리 덕에 풀려나지 않는다")
    void activeBucket_isKept() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        long t0 = System.nanoTime();
        for (int i = 0; i < 21; i++) {
            limiter.tryAcquire("203.0.113.55", t0 + 10 * MINUTE + i);
        }

        limiter.tryAcquire("203.0.113.56", t0 + 12 * MINUTE);

        assertThat(limiter.tryAcquire("203.0.113.55", t0 + 12 * MINUTE)).isFalse();
    }
}
