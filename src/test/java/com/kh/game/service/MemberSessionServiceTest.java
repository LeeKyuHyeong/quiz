package com.kh.game.service;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberRepository;
import com.kh.game.security.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberSessionServiceTest {

    private static Member member(long memberId) {
        Member m = new Member();
        m.setId(memberId);
        m.setEmail("m" + memberId + "@test.com");
        return m;
    }

    private static MemberRepository repositoryWith(Member... members) {
        MemberRepository repository = mock(MemberRepository.class);
        for (Member m : members) {
            when(repository.findById(m.getId())).thenReturn(Optional.of(m));
        }
        return repository;
    }

    @Test
    @DisplayName("대상 회원의 세션만 만료시키고 다른 회원은 건드리지 않는다")
    void expiresOnlyTargetSessions() {
        SessionRegistry registry = mock(SessionRegistry.class);
        Member targetMember = member(10L);
        CustomUserDetails target = new CustomUserDetails(targetMember);
        CustomUserDetails other = new CustomUserDetails(member(20L));
        SessionInformation targetSession = new SessionInformation(target, "s-target", new Date());
        SessionInformation otherSession = new SessionInformation(other, "s-other", new Date());

        // 메모리 레지스트리는 principal 을 equals(회원 ID) 로 찾는다 — 서비스가 새로 만든 CustomUserDetails 도 같은 회원이면 맞아야 한다
        when(registry.getAllSessions(eq(target), eq(false))).thenReturn(List.of(targetSession));
        when(registry.getAllSessions(eq(other), eq(false))).thenReturn(List.of(otherSession));

        int expired = new MemberSessionService(registry, repositoryWith(targetMember)).expireSessions(10L);

        assertThat(expired).isEqualTo(1);
        assertThat(targetSession.isExpired()).isTrue();
        assertThat(otherSession.isExpired()).isFalse();
        Mockito.verify(registry, Mockito.never()).getAllPrincipals();  // DB 레지스트리는 지원하지 않는다
    }

    @Test
    @DisplayName("레지스트리에 주는 principal 은 로그인 주체와 같은 모양이다 — DB 레지스트리가 쓰는 이름(이메일)이 나오고 비밀번호는 없다")
    void looksUpByPrincipalShapedLikeTheLoginPrincipal() {
        SessionRegistry registry = mock(SessionRegistry.class);
        Member targetMember = member(10L);
        targetMember.setPassword("hash");
        when(registry.getAllSessions(any(), eq(false))).thenReturn(List.of());

        new MemberSessionService(registry, repositoryWith(targetMember)).expireSessions(10L);

        ArgumentCaptor<Object> principal = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(registry).getAllSessions(principal.capture(), eq(false));
        assertThat(principal.getValue()).isInstanceOf(CustomUserDetails.class);
        CustomUserDetails details = (CustomUserDetails) principal.getValue();
        assertThat(details.getUsername()).isEqualTo("m10@test.com");
        assertThat(details.getMemberId()).isEqualTo(10L);
        assertThat(details.getPassword()).isNull();
    }

    @Test
    @DisplayName("없는 회원이면 아무 세션도 만료시키지 않고 0")
    void unknownMember_expiresNothing() {
        SessionRegistry registry = mock(SessionRegistry.class);

        int expired = new MemberSessionService(registry, repositoryWith()).expireSessions(99L);

        assertThat(expired).isZero();
        Mockito.verifyNoInteractions(registry);
    }
}
