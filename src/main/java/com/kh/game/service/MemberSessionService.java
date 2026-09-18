package com.kh.game.service;

import com.kh.game.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * 특정 회원의 로그인 세션을 서버 쪽에서 만료시킨다.
 * 관리자 "세션 강제 종료", 비밀번호 초기화·재설정 뒤 기존 세션 무효화에 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSessionService {

    private final SessionRegistry sessionRegistry;

    /** @return 만료시킨 세션 수 */
    public int expireSessions(Long memberId) {
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails details
                    && memberId.equals(details.getMemberId())) {
                for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                    session.expireNow();
                    expired++;
                }
            }
        }
        log.info("Member sessions expired: memberId={}, count={}", memberId, expired);
        return expired;
    }
}
