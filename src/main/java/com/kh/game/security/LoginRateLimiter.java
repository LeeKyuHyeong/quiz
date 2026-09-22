package com.kh.game.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로그인 관련 엔드포인트의 IP 기반 Rate Limiting
 *
 * 적용 대상:
 * - /auth/check-login (로그인 사전 검증)
 * - /auth/check-email (이메일 중복 확인)
 * - /auth/register (회원가입)
 *
 * 정책:
 * - IP당 분당 N회 제한
 * - 토큰 버킷 알고리즘 (bucket4j)
 * - 메모리 기반 (단일 서버 환경. 멀티 서버는 Redis 백엔드로 전환 필요)
 * - 10분 넘게 쓰이지 않은 IP 의 버킷은 지운다
 * - 시계는 주입할 수 있다(버킷 보충과 정리가 같은 시계를 본다). 운영은 단조 시계(System.nanoTime), 테스트는 손으로 돌리는 시계 —
 *   느린 PC 에서 로그인 20회가 3초를 넘기면 보충된 토큰으로 21번째가 통과해 결과가 시간에 좌우됐다(2026-09-22 O-023)
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * IP 별 마지막 사용 시각(nanoTime). 오래 쓰이지 않은 버킷을 지우는 기준이다 — 지우지 않으면 접속한 IP 수만큼 계속 쌓인다.
     * 버킷은 1분이면 가득 차므로, 10분 쉰 버킷을 지우고 새로 만들어도 제한 결과는 같다.
     */
    private final ConcurrentHashMap<String, Long> lastUsed = new ConcurrentHashMap<>();
    private final AtomicLong lastEviction;

    public LoginRateLimiter() {
        this(TimeMeter.SYSTEM_NANOTIME);
    }

    /** 시계를 바꿔 끼운다 — 테스트가 시간을 돌리기 위해. 운영은 기본 생성자(Spring 은 기본 생성자를 쓴다). */
    LoginRateLimiter(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
        this.lastEviction = new AtomicLong(timeMeter.currentTimeNanos());
    }

    private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final long EVICTION_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    /**
     * 분당 허용 요청 수
     */
    private static final long REQUESTS_PER_MINUTE = 20L;

    /**
     * 화이트리스트 IP — Rate Limit 적용 제외
     * - 로컬호스트 (IPv4/IPv6)
     * - application-{profile}.yml의 security.rate-limit.whitelist 프로퍼티로 추가 가능
     */
    private static final Set<String> DEFAULT_WHITELIST = Set.of(
            "127.0.0.1",
            "0:0:0:0:0:0:0:1",
            "::1"
    );

    private final Set<String> whitelist = new HashSet<>(DEFAULT_WHITELIST);

    /**
     * application.yml에서 추가 화이트리스트 IP 주입
     * 예: security.rate-limit.whitelist=1.2.3.4,5.6.7.8
     */
    @Value("${security.rate-limit.whitelist:}")
    public void setExtraWhitelist(String csv) {
        if (csv == null || csv.isBlank()) return;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(whitelist::add);
    }

    private boolean isWhitelisted(String ip) {
        return ip != null && whitelist.contains(ip);
    }

    /**
     * 클라이언트 IP. 프록시 헤더를 직접 읽지 않는다 — X-Forwarded-For 의 앞쪽 값은 클라이언트가 마음대로 넣을 수 있고
     * (nginx 는 받은 헤더 뒤에 실제 IP 를 덧붙인다), 그 값을 쓰면 요청마다 헤더를 바꿔 제한을 피할 수 있다.
     * 운영은 server.forward-headers-strategy=native 라 Tomcat(RemoteIpValve)이 헤더를 오른쪽부터 읽어
     * 내부 프록시를 건너뛴 실제 IP 를 getRemoteAddr() 에 넣어 준다.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * IP별 버킷 생성/조회. 첫 요청 시 새 버킷 생성.
     */
    private Bucket resolveBucket(String ip) {
        return buckets.computeIfAbsent(ip, k -> Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.builder()
                        .capacity(REQUESTS_PER_MINUTE)
                        .refillGreedy(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build());
    }

    /**
     * 요청 시도. 허용되면 true, 한도 초과면 false.
     */
    public boolean tryAcquire(String ip) {
        return tryAcquire(ip, timeMeter.currentTimeNanos());
    }

    /** nowNanos 는 버킷 정리 판단에 쓴다. 버킷 보충은 생성자로 받은 시계를 본다. */
    boolean tryAcquire(String ip, long nowNanos) {
        if (isWhitelisted(ip)) {
            log.debug("[RateLimit] IP={} 화이트리스트 통과", ip);
            return true;
        }

        evictIdleBuckets(nowNanos);
        lastUsed.put(ip, nowNanos);
        Bucket bucket = resolveBucket(ip);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            log.debug("[RateLimit] IP={} 허용 (남은 토큰={}/{})",
                    ip, probe.getRemainingTokens(), REQUESTS_PER_MINUTE);
            return true;
        }

        long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        log.warn("[RateLimit] IP={} 차단 (한도 초과, 다음 토큰까지 {}초 대기 필요)",
                ip, waitSeconds);
        return false;
    }

    /** 요청 처리 중에 1분에 한 번만 돈다 — 별도 스케줄러를 두지 않는다. */
    private void evictIdleBuckets(long nowNanos) {
        long last = lastEviction.get();
        if (nowNanos - last < EVICTION_INTERVAL_NANOS || !lastEviction.compareAndSet(last, nowNanos)) {
            return;
        }
        lastUsed.forEach((ip, usedAt) -> {
            if (nowNanos - usedAt > IDLE_NANOS && lastUsed.remove(ip, usedAt)) {
                buckets.remove(ip);
            }
        });
    }

    int bucketCount() {
        return buckets.size();
    }
}
