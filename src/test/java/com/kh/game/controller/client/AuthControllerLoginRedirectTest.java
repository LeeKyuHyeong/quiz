package com.kh.game.controller.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 페이지의 redirect 파라미터는 이 사이트 안의 경로만 허용해야 한다.
 *
 * 배경: 값이 검증 없이 hidden input 으로 렌더링되고 auth-login.js 가 그대로 window.location.href 에 넣어,
 * ?redirect=https://evil.example 로 로그인 뒤 외부 사이트로 보낼 수 있었다 (오픈 리다이렉트, 2026-09-16 발견).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuthController - 로그인 redirect 파라미터")
class AuthControllerLoginRedirectTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "사이트 내부 경로 ''{0}'' 은 그대로 전달된다")
    @ValueSource(strings = {"/game/multi", "/game/multi/join?code=ABC123", "/mypage"})
    void internalPath_isKept(String redirect) throws Exception {
        mockMvc.perform(get("/auth/login").param("redirect", redirect))
                .andExpect(status().isOk())
                .andExpect(model().attribute("redirect", redirect));
    }

    @ParameterizedTest(name = "외부·비정상 값 ''{0}'' 은 버려진다")
    @ValueSource(strings = {
            "https://evil.example/phish",
            "//evil.example",
            "/\\evil.example",
            "javascript:alert(1)",
            "game/multi",
            "/game/multi\r\nSet-Cookie:x=y"
    })
    void externalOrMalformed_isDropped(String redirect) throws Exception {
        mockMvc.perform(get("/auth/login").param("redirect", redirect))
                .andExpect(status().isOk())
                .andExpect(model().attribute("redirect", (Object) null));
    }
}
