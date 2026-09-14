package com.kh.game.security;

import com.kh.game.config.SecurityConfig;
import com.kh.game.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityFilterChainTest {

    private MockMvc mockMvc;

    @Controller
    static class TestController {
        @GetMapping("/")
        @ResponseBody
        String home() { return "home"; }

        @GetMapping("/auth/login")
        @ResponseBody
        String login() { return "login"; }

        @GetMapping("/game/guess/setup")
        @ResponseBody
        String game() { return "game"; }

        @GetMapping("/admin/dashboard")
        @ResponseBody
        String admin() { return "admin"; }

        @GetMapping("/admin/login")
        @ResponseBody
        String adminLogin() { return "adminLogin"; }

        @GetMapping("/mypage")
        @ResponseBody
        String mypage() { return "mypage"; }

        @PostMapping("/auth/login-process")
        @ResponseBody
        String loginPost() { return "loginPost"; }

        @PostMapping("/ws/123/abcdefgh/xhr_streaming")
        @ResponseBody
        String sockJsXhr() { return "sockjs"; }
    }

    @Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class TestConfig {
        @Bean
        public MemberService memberService() {
            return mock(MemberService.class);
        }

        @Bean
        public CustomAuthenticationSuccessHandler successHandler() {
            return new CustomAuthenticationSuccessHandler(memberService());
        }

        @Bean
        public CustomAuthenticationFailureHandler failureHandler() {
            return new CustomAuthenticationFailureHandler();
        }
    }

    @BeforeEach
    void setUp() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityConfig.class, TestConfig.class, TestController.class);
        context.refresh();

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // ========== 공개 URL 테스트 ==========

    @Test
    @DisplayName("홈페이지는 인증 없이 접근 가능")
    void homePage_noAuth_accessible() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인 페이지는 인증 없이 접근 가능")
    void loginPage_noAuth_accessible() throws Exception {
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("게임 페이지는 인증 없이 접근 가능")
    void gamePage_noAuth_accessible() throws Exception {
        mockMvc.perform(get("/game/guess/setup"))
                .andExpect(status().isOk());
    }

    // ========== Phase 4: CSRF 활성화 확인 ==========

    @Test
    @DisplayName("Phase 4: CSRF 토큰 없이 POST 시 403")
    void csrf_enabled_postWithoutToken_forbidden() throws Exception {
        mockMvc.perform(post("/auth/login-process")
                        .param("email", "test@test.com")
                        .param("password", "1234"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Phase 4: CSRF 토큰 포함 POST 시 정상 처리")
    void csrf_enabled_postWithToken_ok() throws Exception {
        mockMvc.perform(post("/auth/login-process")
                        .with(csrf())
                        .param("email", "test@test.com")
                        .param("password", "1234"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SockJS 폴백 전송(/ws/**) POST는 CSRF 토큰 없이도 허용")
    void csrf_sockJsFallbackPost_ignored() throws Exception {
        mockMvc.perform(post("/ws/123/abcdefgh/xhr_streaming"))
                .andExpect(status().isOk());
    }

    // ========== Phase 3: 인가 규칙 테스트 ==========

    @Nested
    @DisplayName("관리자 페이지 인가")
    class AdminAuthorization {

        @Test
        @DisplayName("비인증 사용자 → 관리자 페이지 접근 시 로그인 페이지로 리다이렉트")
        void adminPage_noAuth_redirectsToLogin() throws Exception {
            mockMvc.perform(get("/admin/dashboard"))
                    .andExpect(status().is3xxRedirection());
        }

        @Test
        @DisplayName("관리자 로그인 페이지는 인증 없이 접근 가능")
        void adminLoginPage_noAuth_accessible() throws Exception {
            mockMvc.perform(get("/admin/login"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("일반 사용자 → 관리자 페이지 접근 시 403")
        void adminPage_userRole_forbidden() throws Exception {
            mockMvc.perform(get("/admin/dashboard").with(user("user@test.com").roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("관리자 → 관리자 페이지 접근 가능")
        void adminPage_adminRole_accessible() throws Exception {
            mockMvc.perform(get("/admin/dashboard").with(user("admin@test.com").roles("ADMIN")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("마이페이지 인가")
    class MyPageAuthorization {

        @Test
        @DisplayName("비인증 사용자 → 마이페이지 접근 시 로그인 페이지로 리다이렉트")
        void myPage_noAuth_redirectsToLogin() throws Exception {
            mockMvc.perform(get("/mypage"))
                    .andExpect(status().is3xxRedirection());
        }

        @Test
        @DisplayName("인증된 사용자 → 마이페이지 접근 가능")
        void myPage_authenticated_accessible() throws Exception {
            mockMvc.perform(get("/mypage").with(user("user@test.com").roles("USER")))
                    .andExpect(status().isOk());
        }
    }

    // ========== formLogin 설정 확인 ==========

    @Test
    @DisplayName("로그인 처리 URL이 /auth/login-process로 설정")
    void loginProcessUrl_configured() throws Exception {
        mockMvc.perform(post("/auth/login-process")
                        .with(csrf())
                        .param("email", "nonexistent@test.com")
                        .param("password", "wrong"))
                .andExpect(status().isOk());
    }
}
