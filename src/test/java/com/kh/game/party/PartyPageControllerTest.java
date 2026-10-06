package com.kh.game.party;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파티 화면(HTML) 경로. 조회·조작 API 는 PartyControllerTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("파티 화면 (/admin/party/console)")
class PartyPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[정상] 관리자는 콘솔 화면을 받고, 화면에 CSRF 토큰과 콘솔 스크립트가 실린다")
    void adminGetsConsole() throws Exception {
        mockMvc.perform(get("/admin/party/console").with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("/js/admin/party-console.js")))
                .andExpect(content().string(containsString("id=\"partyConsole\"")));
    }

    @Test
    @DisplayName("[권한] 로그인하지 않으면 로그인 화면으로 보내진다")
    void anonymousIsRedirected() throws Exception {
        mockMvc.perform(get("/admin/party/console")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("[권한] 일반 회원은 403 이다")
    void memberIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/party/console").with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[정상] 관리자는 보드 화면을 받는다 — 항상 다크(.game-page), 보드 스크립트만 싣고 콘솔 스크립트는 없다")
    void adminGetsBoard() throws Exception {
        mockMvc.perform(get("/admin/party/board").with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("game-page")))
                .andExpect(content().string(containsString("/js/admin/party-board.js")))
                .andExpect(content().string(not(containsString("party-console.js"))))
                .andExpect(content().string(containsString("id=\"partyBoard\"")));
    }

    @Test
    @DisplayName("[권한] 보드도 비로그인은 로그인 화면, 일반 회원은 403 이다")
    void boardNeedsAdmin() throws Exception {
        mockMvc.perform(get("/admin/party/board")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/party/board").with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
