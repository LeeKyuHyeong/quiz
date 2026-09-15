package com.kh.game.controller.client;

import com.kh.game.entity.EmailVerification;
import com.kh.game.entity.Member;
import com.kh.game.repository.EmailVerificationRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.service.BrevoMailClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("비밀번호 재설정 — 이메일 인증 흐름")
class AuthControllerPasswordResetTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired EmailVerificationRepository verificationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean BrevoMailClient mailClient;

    private static final String EMAIL = "reset@test.com";

    @BeforeEach
    void setUp() {
        verificationRepository.deleteAll();
        memberRepository.findByEmail(EMAIL).ifPresent(memberRepository::delete);
        Member m = new Member();
        m.setEmail(EMAIL);
        m.setPassword(passwordEncoder.encode("oldpass"));
        m.setNickname("리셋");
        m.setUsername("reset-user");
        memberRepository.save(m);
    }

    private String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    @Test
    @DisplayName("재설정 페이지는 로그인 없이 열린다")
    void page_isPublic() throws Exception {
        mockMvc.perform(get("/auth/password-reset"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("코드 발송 → 코드 확인 → 새 비밀번호 저장까지 이어진다")
    void fullFlow_changesPassword() throws Exception {
        mockMvc.perform(post("/auth/password-reset/send-code").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(mailClient).send(eq(EMAIL), anyString(), anyString());

        EmailVerification record = verificationRepository
                .findFirstByEmailAndVerifiedFalseOrderByCreatedAtDesc(EMAIL).orElseThrow();

        mockMvc.perform(post("/auth/verify-code").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", EMAIL, "code", record.getCode())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", EMAIL, "newPassword", "newpass1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Member updated = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(passwordEncoder.matches("newpass1", updated.getPassword())).isTrue();
        assertThat(verificationRepository.findFirstByEmailAndVerifiedTrueOrderByVerifiedAtDesc(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("인증 없이 재설정을 요청하면 거부되고 비밀번호는 그대로")
    void withoutVerification_rejected() throws Exception {
        mockMvc.perform(post("/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content(json("email", EMAIL, "newPassword", "newpass1")))
                .andExpect(status().isBadRequest());

        Member unchanged = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(passwordEncoder.matches("oldpass", unchanged.getPassword())).isTrue();
    }

    @Test
    @DisplayName("가입되지 않은 이메일로는 코드가 발송되지 않는다")
    void unknownEmail_noMail() throws Exception {
        mockMvc.perform(post("/auth/password-reset/send-code").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content(json("email", "nobody@test.com")))
                .andExpect(status().isBadRequest());
        verify(mailClient, org.mockito.Mockito.never()).send(anyString(), anyString(), anyString());
    }
}
