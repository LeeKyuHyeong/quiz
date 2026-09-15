package com.kh.game.service;

import com.kh.game.entity.Member;
import com.kh.game.security.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberSessionServiceTest {

    private static CustomUserDetails principal(long memberId) {
        Member m = new Member();
        m.setId(memberId);
        m.setEmail("m" + memberId + "@test.com");
        return new CustomUserDetails(m);
    }

    @Test
    @DisplayName("대상 회원의 세션만 만료시키고 다른 회원은 건드리지 않는다")
    void expiresOnlyTargetSessions() {
        SessionRegistry registry = mock(SessionRegistry.class);
        CustomUserDetails target = principal(10L);
        CustomUserDetails other = principal(20L);
        SessionInformation targetSession = new SessionInformation(target, "s-target", new Date());
        SessionInformation otherSession = new SessionInformation(other, "s-other", new Date());

        when(registry.getAllPrincipals()).thenReturn(List.of(target, other, "anonymous"));
        when(registry.getAllSessions(target, false)).thenReturn(List.of(targetSession));
        when(registry.getAllSessions(other, false)).thenReturn(List.of(otherSession));

        int expired = new MemberSessionService(registry).expireSessions(10L);

        assertThat(expired).isEqualTo(1);
        assertThat(targetSession.isExpired()).isTrue();
        assertThat(otherSession.isExpired()).isFalse();
        Mockito.verify(registry, Mockito.never()).getAllSessions(other, true);
    }
}
