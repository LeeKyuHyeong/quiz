package com.kh.game.security;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    private Member activeMember;
    private Member bannedMember;
    private Member inactiveMember;
    private Member adminMember;

    @BeforeEach
    void setUp() {
        activeMember = new Member();
        activeMember.setId(1L);
        activeMember.setEmail("user@test.com");
        activeMember.setPassword("encodedPassword");
        activeMember.setNickname("testUser");
        activeMember.setUsername("testUser");
        activeMember.setRole(Member.MemberRole.USER);
        activeMember.setStatus(Member.MemberStatus.ACTIVE);

        bannedMember = new Member();
        bannedMember.setId(2L);
        bannedMember.setEmail("banned@test.com");
        bannedMember.setPassword("encodedPassword");
        bannedMember.setNickname("bannedUser");
        bannedMember.setUsername("bannedUser");
        bannedMember.setRole(Member.MemberRole.USER);
        bannedMember.setStatus(Member.MemberStatus.BANNED);

        inactiveMember = new Member();
        inactiveMember.setId(3L);
        inactiveMember.setEmail("inactive@test.com");
        inactiveMember.setPassword("encodedPassword");
        inactiveMember.setNickname("inactiveUser");
        inactiveMember.setUsername("inactiveUser");
        inactiveMember.setRole(Member.MemberRole.USER);
        inactiveMember.setStatus(Member.MemberStatus.INACTIVE);

        adminMember = new Member();
        adminMember.setId(4L);
        adminMember.setEmail("admin@test.com");
        adminMember.setPassword("encodedPassword");
        adminMember.setNickname("admin");
        adminMember.setUsername("admin");
        adminMember.setRole(Member.MemberRole.ADMIN);
        adminMember.setStatus(Member.MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("활성 사용자 email로 조회 시 UserDetails 반환")
    void loadUserByUsername_activeUser_returnsUserDetails() {
        when(memberRepository.findByEmail("user@test.com")).thenReturn(Optional.of(activeMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("user@test.com");

        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("user@test.com");
        assertThat(userDetails.getPassword()).isEqualTo("encodedPassword");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
    }

    @Test
    @DisplayName("USER 역할이면 ROLE_USER 권한 보유")
    void loadUserByUsername_userRole_hasRoleUser() {
        when(memberRepository.findByEmail("user@test.com")).thenReturn(Optional.of(activeMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("user@test.com");

        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("ADMIN 역할이면 ROLE_ADMIN 권한 보유")
    void loadUserByUsername_adminRole_hasRoleAdmin() {
        when(memberRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(adminMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("admin@test.com");

        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("존재하지 않는 email이면 UsernameNotFoundException")
    void loadUserByUsername_notFound_throwsException() {
        when(memberRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("unknown@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("BANNED 사용자는 isAccountNonLocked=false")
    void loadUserByUsername_bannedUser_accountLocked() {
        when(memberRepository.findByEmail("banned@test.com")).thenReturn(Optional.of(bannedMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("banned@test.com");

        assertThat(userDetails.isAccountNonLocked()).isFalse();
        assertThat(userDetails.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("INACTIVE 사용자는 isEnabled=false")
    void loadUserByUsername_inactiveUser_disabled() {
        when(memberRepository.findByEmail("inactive@test.com")).thenReturn(Optional.of(inactiveMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("inactive@test.com");

        assertThat(userDetails.isEnabled()).isFalse();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
    }

    @Test
    @DisplayName("CustomUserDetails에서 Member 엔티티 접근 가능")
    void loadUserByUsername_canAccessMember() {
        when(memberRepository.findByEmail("user@test.com")).thenReturn(Optional.of(activeMember));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("user@test.com");

        assertThat(userDetails).isInstanceOf(CustomUserDetails.class);
        CustomUserDetails customUserDetails = (CustomUserDetails) userDetails;
        assertThat(customUserDetails.getMember().getId()).isEqualTo(1L);
        assertThat(customUserDetails.getMember().getNickname()).isEqualTo("testUser");
    }

    @Test
    @DisplayName("이메일 형식이 잘못되면 UsernameNotFoundException (Repository 접근 안 함)")
    void loadUserByUsername_invalidFormat_throwsException() {
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("not-an-email"))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("SQLi 페이로드가 포함된 이메일은 UsernameNotFoundException")
    void loadUserByUsername_sqlInjectionPayload_throwsException() {
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("admin' OR 1=1--"))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("null/공백 이메일은 UsernameNotFoundException")
    void loadUserByUsername_blankEmail_throwsException() {
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername(""))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("   "))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(memberRepository);
    }
}
