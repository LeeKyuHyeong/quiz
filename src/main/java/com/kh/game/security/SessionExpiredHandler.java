package com.kh.game.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

import java.io.IOException;

/**
 * 만료 표시된 세션(다른 기기 로그인, 관리자 조치)으로 들어온 요청의 응답.
 * 화면 이동은 로그인 페이지로 보내고, fetch 요청은 401 JSON 으로 답한다 —
 * fetch 에 302 를 주면 로그인 HTML 을 200 으로 받아 화면이 조용히 실패한다 (common.js 가 SESSION_INVALIDATED 를 기다린다).
 */
public class SessionExpiredHandler implements SessionInformationExpiredStrategy {

    public static final String EXPIRED_URL = "/auth/login?expired=true";
    public static final String MESSAGE = "다른 기기에서 로그인했거나 관리자 조치로 현재 세션이 종료되었습니다.";

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletResponse response = event.getResponse();
        String accept = event.getRequest().getHeader("Accept");

        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect(event.getRequest().getContextPath() + EXPIRED_URL);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"SESSION_INVALIDATED\",\"message\":\"" + MESSAGE + "\"}");
    }
}
