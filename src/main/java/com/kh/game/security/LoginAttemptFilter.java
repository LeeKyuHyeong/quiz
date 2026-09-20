package com.kh.game.security;

import com.kh.game.service.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * 로그인 요청(POST /auth/login-process)이 비밀번호 검사에 닿기 전에 거른다.
 * ① IP 요청 제한 — 값싼 대량 요청을 거른다 ② 계정 기준 연속 실패 잠금 — IP 를 바꿔 오는 대입을 막는다.
 * CsrfFilter 뒤에 있으므로 CSRF 토큰이 틀린 요청은 횟수에 들어가지 않는다.
 */
public class LoginAttemptFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN_REQUEST = new AntPathRequestMatcher("/auth/login-process", "POST");

    private final LoginRateLimiter loginRateLimiter;
    private final LoginAttemptService loginAttemptService;

    public LoginAttemptFilter(LoginRateLimiter loginRateLimiter, LoginAttemptService loginAttemptService) {
        this.loginRateLimiter = loginRateLimiter;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            writeFailure(response, "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요.");
            return;
        }

        Optional<Duration> locked = loginAttemptService.reserveAttempt(request.getParameter("email"));
        if (locked.isPresent()) {
            // 로그인 화면은 실패를 HTTP 200 + success:false 로 받는다 (CustomAuthenticationFailureHandler 와 같은 형식)
            writeFailure(response, LoginAttemptService.lockedMessage(locked.get()));
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeFailure(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
