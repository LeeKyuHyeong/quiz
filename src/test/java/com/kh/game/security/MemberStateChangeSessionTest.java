package com.kh.game.security;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 주체(CustomUserDetails)가 로그인 시점의 Member 를 세션 내내 들고 있어,
 * 관리자가 회원을 정지·강등해도 그 회원의 기존 세션은 예전 상태·권한으로 계속 동작했다 (2026-09-18 발견).
 * 상태·권한을 바꾸면 그 회원의 세션을 끊어 다시 로그인하게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("관리자가 회원 상태·권한을 바꾸면 그 회원의 기존 세션이 끊긴다")
class MemberStateChangeSessionTest {

    private static final String PASSWORD = "Passw0rd!stale";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberLoginHistoryRepository loginHistoryRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            loginHistoryRepository.deleteAll(loginHistoryRepository
                    .findByMemberIdOrderByCreatedAtDesc(id, Pageable.unpaged()).getContent());
            memberRepository.deleteById(id);
        }
    }

    private Member createMember(Member.MemberRole role) {
        Member member = new Member();
        member.setEmail("stale-" + System.nanoTime() + "@test.com");
        member.setPassword(passwordEncoder.encode(PASSWORD));
        member.setNickname("stale" + (System.nanoTime() % 1000000));
        member.setUsername("stale");
        member.setRole(role);
        member.setStatus(Member.MemberStatus.ACTIVE);
        Member saved = memberRepository.save(member);
        createdIds.add(saved.getId());
        return saved;
    }

    private MockHttpSession login(Member member) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login-process").with(csrf())
                        .param("email", member.getEmail())
                        .param("password", PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("로그인 성공 후 세션").isNotNull();
        mockMvc.perform(get("/auth/status").session(session))
                .andExpect(jsonPath("$.isLoggedIn").value(true));
        return session;
    }

    @Test
    @DisplayName("정지(BANNED)된 회원은 기존 세션으로 더 이상 이용할 수 없고, 다시 로그인할 수도 없다")
    void bannedMember_losesExistingSession() throws Exception {
        MockHttpSession adminSession = login(createMember(Member.MemberRole.ADMIN));
        Member target = createMember(Member.MemberRole.USER);
        MockHttpSession targetSession = login(target);

        mockMvc.perform(post("/admin/member/update-status/" + target.getId()).with(csrf())
                        .session(adminSession).param("status", "BANNED"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mypage").session(targetSession).accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/auth/login?expired=true"));
        mockMvc.perform(post("/auth/login-process").with(csrf())
                        .param("email", target.getEmail()).param("password", PASSWORD))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("정지된 계정입니다."));
    }

    @Test
    @DisplayName("ADMIN 에서 USER 로 강등되면 기존 세션이 끊기고, 다시 로그인해도 /admin/** 에 들어갈 수 없다")
    void demotedAdmin_losesAdminAccess() throws Exception {
        MockHttpSession actorSession = login(createMember(Member.MemberRole.ADMIN));
        Member target = createMember(Member.MemberRole.ADMIN);
        MockHttpSession targetSession = login(target);
        mockMvc.perform(get("/admin/member/detail/" + target.getId()).session(targetSession))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/member/update-role/" + target.getId()).with(csrf())
                        .session(actorSession).param("role", "USER"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/admin/member/detail/" + target.getId()).session(targetSession)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/auth/login?expired=true"));
        MockHttpSession reLogin = login(target);
        mockMvc.perform(get("/admin/member/detail/" + target.getId()).session(reLogin))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("상태를 ACTIVE 로 되돌리는 변경은 세션을 끊지 않는다")
    void activatingMember_keepsSession() throws Exception {
        MockHttpSession adminSession = login(createMember(Member.MemberRole.ADMIN));
        Member target = createMember(Member.MemberRole.USER);
        MockHttpSession targetSession = login(target);

        mockMvc.perform(post("/admin/member/update-status/" + target.getId()).with(csrf())
                        .session(adminSession).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/auth/status").session(targetSession))
                .andExpect(jsonPath("$.isLoggedIn").value(true));
    }
}
