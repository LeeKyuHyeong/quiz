package com.kh.game.security;

import com.kh.game.entity.Member;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final Member member;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
    }

    @Override
    public String getPassword() {
        return member.getPassword();
    }

    @Override
    public String getUsername() {
        return member.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return member.getStatus() != Member.MemberStatus.BANNED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return member.getStatus() == Member.MemberStatus.ACTIVE;
    }

    /**
     * SessionRegistry 는 principal 을 Map 키로 쓴다. 로그인마다 새 인스턴스가 만들어지므로
     * 회원 ID 로 동등성을 정의하지 않으면 같은 회원의 세션들이 서로 다른 사람으로 취급돼 1계정 1세션 제한이 동작하지 않는다.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomUserDetails other)) return false;
        return member.getId() != null && member.getId().equals(other.member.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(member.getId());
    }
}
