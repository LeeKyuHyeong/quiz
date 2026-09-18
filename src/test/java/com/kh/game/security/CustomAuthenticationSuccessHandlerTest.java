package com.kh.game.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kh.game.entity.Member;
import com.kh.game.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomAuthenticationSuccessHandlerTest {

    @Mock
    private MemberService memberService;

    private CustomAuthenticationSuccessHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private ObjectMapper objectMapper;

    private Member testMember;
    private CustomUserDetails userDetails;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        handler = new CustomAuthenticationSuccessHandler(memberService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        objectMapper = new ObjectMapper();

        testMember = new Member();
        testMember.setId(1L);
        testMember.setEmail("user@test.com");
        testMember.setNickname("testUser");
        testMember.setRole(Member.MemberRole.USER);
        testMember.setStatus(Member.MemberStatus.ACTIVE);

        userDetails = new CustomUserDetails(testMember);
        authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        // 주체는 식별 값만 들고 있다 — 닉네임은 핸들러가 회원을 읽어서 채운다
        when(memberService.findLoginMember(userDetails)).thenReturn(testMember);
    }

    @Test
    @DisplayName("로그인 성공 시 JSON 응답 반환")
    void onAuthenticationSuccess_returnsJsonResponse() throws Exception {
        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        Map<String, Object> result = objectMapper.readValue(response.getContentAsString(), Map.class);
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("nickname")).isEqualTo("testUser");
    }

    @Test
    @DisplayName("로그인 성공 시 로그인 이력 기록 호출")
    void onAuthenticationSuccess_recordsLoginHistory() throws Exception {
        request.addHeader("User-Agent", "TestBrowser/1.0");
        request.setRemoteAddr("127.0.0.1");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(memberService).recordLoginSuccess(eq(1L), anyString(), eq("TestBrowser/1.0"));
    }

}
