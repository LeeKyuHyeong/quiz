package com.kh.game.service;

import com.kh.game.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 계정 기준 로그인 실패 제한 — 연속 5회 실패하면 5분 동안 로그인을 받지 않는다.
 * IP 요청 제한(LoginRateLimiter)은 IP 를 바꾸면 뚫리므로, 한 계정에 대한 비밀번호 대입은 여기서 막는다.
 * 상태는 member 테이블에 둔다 — 배포·인스턴스 수와 무관하게 유지된다.
 * 횟수는 로그인 성공·비밀번호 재설정 때 MemberService 가 0 으로 되돌린다. 잠금은 시간이 지나도 풀린다. 관리자가 풀어 주는 절차와 영구 잠금은 두지 않는다
 * (남의 계정을 일부러 틀려 잠그는 것을 막을 수 없으므로 짧게 두고 본인이 풀 수 있게 한다).
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final MemberRepository memberRepository;

    /**
     * 비밀번호를 검사하기 전에 부른다. 시도 1회를 미리 세고, 잠겨 있으면 남은 시간을 돌려준다.
     * 호출자의 트랜잭션이 로그인 실패 예외로 롤백돼도 센 횟수는 남아야 하므로 별도 트랜잭션이다.
     *
     * @return 잠겨 있으면 남은 시간, 시도해도 되면 empty (없는 이메일·정지 계정은 세지 않고 empty)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Duration> reserveAttempt(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        memberRepository.releaseExpiredLoginLock(email, now);
        if (memberRepository.reserveLoginAttempt(email, MAX_FAILURES, now.plus(LOCK_DURATION)) == 1) {
            return Optional.empty();
        }
        return memberRepository.findLoginLockedUntil(email)
                .filter(until -> until.isAfter(now))
                .map(until -> Duration.between(now, until));
    }

    public static String lockedMessage(Duration remaining) {
        long minutes = Math.max(1, (remaining.getSeconds() + 59) / 60);
        return "비밀번호를 " + MAX_FAILURES + "회 연속 틀려 로그인이 잠겼습니다. "
                + minutes + "분 뒤에 다시 시도하거나 비밀번호를 재설정해주세요.";
    }
}
