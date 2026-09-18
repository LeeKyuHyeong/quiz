package com.kh.game.security;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 로그인 세션에 무엇이 실리는가 (2026-09-19).
 *
 * 로그인 주체가 Member 엔티티를 통째로 들고 있으면 세션에 비밀번호 해시와 연관 엔티티가 함께 실리고,
 * 로그인 뒤에 바뀐 회원 정보가 그 세션에 반영되지 않는다. 세션을 외부 저장소(Redis)로 옮기려면
 * 세션 내용이 직렬화 가능하고, 자격 증명을 포함하지 않아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("로그인 세션에 실리는 내용")
class LoginPrincipalContentTest {

    private static final String PASSWORD = "Passw0rd!principal";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberLoginHistoryRepository loginHistoryRepository;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Member member;
    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        Member m = new Member();
        m.setEmail("principal-" + System.nanoTime() + "@test.com");
        m.setPassword(passwordEncoder.encode(PASSWORD));
        m.setNickname("before" + (System.nanoTime() % 1000000));
        m.setUsername("principal");
        m.setRole(Member.MemberRole.USER);
        m.setStatus(Member.MemberStatus.ACTIVE);
        member = memberRepository.save(m);

        session = (MockHttpSession) mockMvc.perform(post("/auth/login-process").with(csrf())
                        .param("email", member.getEmail())
                        .param("password", PASSWORD))
                .andReturn().getRequest().getSession(false);
        assertThat(session).as("로그인 성공 후 세션").isNotNull();
    }

    @AfterEach
    void cleanUp() {
        loginHistoryRepository.deleteAll(loginHistoryRepository
                .findByMemberIdOrderByCreatedAtDesc(member.getId(), Pageable.unpaged()).getContent());
        memberRepository.deleteById(member.getId());
    }

    private Authentication authenticationInSession() {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).as("세션의 SecurityContext").isNotNull();
        return context.getAuthentication();
    }

    @Test
    @DisplayName("세션의 로그인 정보는 직렬화할 수 있고, 비밀번호 해시와 Member 엔티티를 포함하지 않는다")
    void sessionContent_isSerializable_andHoldsNoCredentialsOrEntity() throws Exception {
        Authentication authentication = authenticationInSession();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(authentication);
        }
        String serialized = bytes.toString(StandardCharsets.ISO_8859_1);

        assertThat(serialized).doesNotContain(member.getPassword());
        // Member 안에 선언된 enum(MemberRole·MemberStatus)은 이름만 실린다 — 엔티티 인스턴스가 없어야 한다
        assertThat(serialized).doesNotContainPattern("com\\.kh\\.game\\.entity\\.\\w+(?![\\w$])");
        assertThat(((CustomUserDetails) authentication.getPrincipal()).getPassword()).isNull();
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    @DisplayName("직렬화했다가 되살린 로그인 정보는 같은 회원·같은 권한이다 (세션 저장소를 거쳐도 1계정 1세션 판단이 유지된다)")
    void deserializedPrincipal_equalsOriginal() throws Exception {
        Authentication authentication = authenticationInSession();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(authentication);
        }
        Authentication restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Authentication) in.readObject();
        }

        assertThat(restored.getPrincipal()).isEqualTo(authentication.getPrincipal());
        assertThat(((CustomUserDetails) restored.getPrincipal()).getMemberId()).isEqualTo(member.getId());
        assertThat(restored.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("닉네임을 바꾸면 같은 세션의 /auth/status 가 새 닉네임을 돌려준다")
    void nicknameChange_isVisibleInSameSession() throws Exception {
        String newNickname = "after" + (System.nanoTime() % 1000000);

        memberService.updateNickname(member.getId(), newNickname);

        mockMvc.perform(get("/auth/status").session(session))
                .andExpect(jsonPath("$.isLoggedIn").value(true))
                .andExpect(jsonPath("$.memberId").value(member.getId()))
                .andExpect(jsonPath("$.nickname").value(newNickname));
    }
}
