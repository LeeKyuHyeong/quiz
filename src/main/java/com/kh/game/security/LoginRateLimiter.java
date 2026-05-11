package com.kh.game.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
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
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

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
     * 클라이언트 IP 주소를 요청에서 추출
     * - X-Forwarded-For (프록시/로드밸런서 뒤) 우선
     * - 없으면 remoteAddr
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 첫 번째 IP가 실제 클라이언트
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * IP별 버킷 생성/조회. 첫 요청 시 새 버킷 생성.
     */
    private Bucket resolveBucket(String ip) {
        return buckets.computeIfAbsent(ip, k -> Bucket.builder()
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
        if (isWhitelisted(ip)) {
            log.debug("[RateLimit] IP={} 화이트리스트 통과", ip);
            return true;
        }

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
}
