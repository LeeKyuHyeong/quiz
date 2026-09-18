package com.kh.game.security;

import com.kh.game.entity.Member;
import lombok.Getter;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 로그인 주체. 세션에 저장되므로 식별·권한 판단에 필요한 값만 복사해 들고, Member 엔티티는 들지 않는다.
 * 엔티티를 들면 비밀번호 해시와 연관 엔티티가 세션에 실리고, 로그인 뒤에 바뀐 회원 정보가 세션에 반영되지 않는다.
 * 화면에 보여 줄 회원 정보(닉네임 등)는 memberId 로 DB 에서 읽는다.
 *
 * 상태·권한은 로그인 시점 값이다. 관리자가 바꾸면 그 회원의 세션을 끊어 다시 로그인하게 한다 (AdminMemberController).
 */
@Getter
public class CustomUserDetails implements UserDetails, CredentialsContainer, Serializable {

    private static final long serialVersionUID = 1L;

    private final Long memberId;
    private final String email;
    private final Member.MemberRole role;
    private final Member.MemberStatus status;
    private String password;

    public CustomUserDetails(Member member) {
        this.memberId = member.getId();
        this.email = member.getEmail();
        this.role = member.getRole();
        this.status = member.getStatus();
        this.password = member.getPassword();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** 인증이 끝나면 Spring Security 가 호출한다 — 비밀번호 해시를 세션에 남기지 않는다 */
    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != Member.MemberStatus.BANNED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == Member.MemberStatus.ACTIVE;
    }

    /**
     * SessionRegistry 는 principal 을 Map 키로 쓴다. 로그인마다 새 인스턴스가 만들어지므로
     * 회원 ID 로 동등성을 정의하지 않으면 같은 회원의 세션들이 서로 다른 사람으로 취급돼 1계정 1세션 제한이 동작하지 않는다.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomUserDetails other)) return false;
        return memberId != null && memberId.equals(other.memberId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(memberId);
    }
}
