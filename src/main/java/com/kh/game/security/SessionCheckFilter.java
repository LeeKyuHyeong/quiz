package com.kh.game.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 열린 탭이 주기적으로 부르는 로그인 세션 상태 확인 (common.js SessionManager).
 *
 * HttpSession 을 읽지 않고 세션 ID 로만 판단한다. 세션을 읽으면 유휴 시간이 연장돼
 * 탭을 열어 둔 동안 세션이 만료되지 않는다. 그래서 컨트롤러가 아니라 필터에서 바로 답한다 (SecurityConfig 참고).
 *
 * - VALID: 로그인 세션이 살아 있다
 * - SESSION_INVALIDATED: 다른 기기 로그인(1계정 1세션)이나 관리자 조치로 만료 표시됐다
 * - NOT_LOGGED_IN: 로그인한 적 없거나 시간 초과로 끝났다
 */
@RequiredArgsConstructor
public class SessionCheckFilter extends OncePerRequestFilter {

    public static final String PATH = "/auth/validate-session";

    private final SessionRegistry sessionRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body(request));
    }

    private String body(HttpServletRequest request) {
        String sessionId = request.getRequestedSessionId();
        SessionInformation info = (sessionId != null && request.isRequestedSessionIdValid())
                ? sessionRegistry.getSessionInformation(sessionId)
                : null;

        if (info == null) {
            return "{\"valid\":false,\"reason\":\"NOT_LOGGED_IN\"}";
        }
        if (info.isExpired()) {
            return "{\"valid\":false,\"reason\":\"SESSION_INVALIDATED\",\"message\":\"" + SessionExpiredHandler.MESSAGE + "\"}";
        }
        return "{\"valid\":true}";
    }
}
