package com.kh.game.service;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberRepository;
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
    private final MemberRepository memberRepository;

    /** @return 만료시킨 세션 수 */
    public int expireSessions(Long memberId) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null) {
            log.info("Member sessions expired: memberId={}, count=0 (no such member)", memberId);
            return 0;
        }
        // 레지스트리는 principal 로 찾는다. 메모리 레지스트리(SessionRegistryImpl)는 CustomUserDetails.equals(회원 ID) 로,
        // DB 레지스트리(SpringSessionBackedSessionRegistry)는 getUsername(이메일) 인덱스로 — 둘 다 로그인 주체와 같은 모양이면 된다.
        // 전체 principal 을 훑는 getAllPrincipals 는 DB 레지스트리가 지원하지 않는다.
        // 이메일은 로그인 ID 라 바뀌지 않는다는 전제 (바뀌면 옛 이메일로 만든 세션은 여기서 못 찾는다).
        CustomUserDetails principal = new CustomUserDetails(member);
        principal.eraseCredentials();

        int expired = 0;
        for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
            session.expireNow();
            expired++;
        }
        log.info("Member sessions expired: memberId={}, count={}", memberId, expired);
        return expired;
    }
}
