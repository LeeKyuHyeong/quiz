package com.kh.game.controller.client;

import com.kh.game.entity.Member;
import com.kh.game.exception.BusinessException;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.security.LoginRateLimiter;
import com.kh.game.service.EmailVerificationService;
import com.kh.game.service.MemberSessionService;
import com.kh.game.service.MemberService;
import com.kh.game.util.SecurityInputValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;
    private final LoginRateLimiter loginRateLimiter;
    private final EmailVerificationService emailVerificationService;
    private final MemberSessionService memberSessionService;

    private ResponseEntity<Map<String, Object>> tooManyRequests() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect, Model model) {
        // 값은 hidden input 을 거쳐 auth-login.js 가 window.location.href 에 그대로 넣는다 → 사이트 내부 경로만 허용
        model.addAttribute("redirect", sanitizeRedirect(redirect));
        return "client/auth/login";
    }

    /**
     * 로그인 후 이동 경로 검증 (오픈 리다이렉트 방지).
     * 허용: "/" 로 시작하는 사이트 내부 경로. 거부: 절대 URL·스킴, "//host"(프로토콜 상대), 백슬래시, 제어 문자.
     * @return 허용되는 경로, 아니면 null (로그인 후 홈으로)
     */
    static String sanitizeRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return null;
        }
        if (redirect.startsWith("/\\") || redirect.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return null;
        }
        return redirect;
    }

    @PostMapping("/check-login")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkLogin(@RequestBody Map<String, String> request,
                                                          HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        Map<String, Object> result = new HashMap<>();

        String email = request.get("email");
        SecurityInputValidator.validateEmailOrThrow(email);
        MemberService.LoginAttemptResult attemptResult = memberService.checkLoginAttempt(email);

        if (attemptResult.status() == MemberService.LoginAttemptStatus.IN_GAME) {
            result.put("canProceed", false);
            result.put("requireConfirm", true);
            result.put("inGame", true);
            result.put("message", "현재 다른 기기에서 게임이 진행 중입니다. 강제 로그인하시겠습니까?");
        } else {
            result.put("canProceed", true);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/register")
    public String registerPage() {
        return "client/auth/register";
    }

    @PostMapping("/register")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request,
                                                        HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        Map<String, Object> result = new HashMap<>();

        String email = request.get("email");
        String password = request.get("password");
        String nickname = request.get("nickname");
        String username = request.get("username");

        SecurityInputValidator.validateEmailOrThrow(email);
        if (password == null || password.length() < 4) {
            throw new IllegalArgumentException("비밀번호는 4자 이상이어야 합니다.");
        }
        if (nickname == null || nickname.length() < 2 || nickname.length() > 20) {
            throw new IllegalArgumentException("닉네임은 2~20자 이내로 입력해주세요.");
        }
        if (username == null || username.isBlank() || username.length() > 50) {
            throw new IllegalArgumentException("성명은 1~50자 이내로 입력해주세요.");
        }
        // 성명 SQLi 페이로드 차단 (이메일과 동일한 보안 정책 적용)
        if (SecurityInputValidator.containsSqlInjectionPattern(username)
                || SecurityInputValidator.containsSqlInjectionPattern(nickname)) {
            throw new IllegalArgumentException("허용되지 않는 문자가 포함되어 있습니다.");
        }

        if (!emailVerificationService.isRecentlyVerified(email)) {
            throw new BusinessException("이메일 인증이 필요합니다. 인증 코드를 받아 입력해주세요.");
        }

        memberService.register(email, password, nickname, username);
        emailVerificationService.consumeVerification(email);

        result.put("success", true);
        result.put("message", "회원가입이 완료되었습니다.");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/send-verification")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendVerification(@RequestBody Map<String, String> request,
                                                                HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        String email = request.get("email");
        emailVerificationService.sendVerificationCode(email);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "인증 코드를 발송했습니다. 메일을 확인해주세요.");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/verify-code")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> verifyCode(@RequestBody Map<String, String> request,
                                                          HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        String email = request.get("email");
        String code = request.get("code");
        emailVerificationService.verifyCode(email, code);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "이메일 인증이 완료되었습니다.");
        return ResponseEntity.ok(result);
    }

    // ========== 비밀번호 재설정 (로그인 불필요, 이메일 인증) ==========

    @GetMapping("/password-reset")
    public String passwordResetPage(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        // 로그인 상태면 본인 이메일로 고정. 비밀번호는 단방향 해시라 "찾기"는 불가능하고 재설정만 가능하다.
        model.addAttribute("email", userDetails != null ? userDetails.getMember().getEmail() : null);
        return "client/auth/password-reset";
    }

    @PostMapping("/password-reset/send-code")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendPasswordResetCode(@RequestBody Map<String, String> request,
                                                                     HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        emailVerificationService.sendPasswordResetCode(request.get("email"));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "인증 코드를 발송했습니다. 메일을 확인해주세요.");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/password-reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> request,
                                                             HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        String email = request.get("email");
        String newPassword = request.get("newPassword");

        SecurityInputValidator.validateEmailOrThrow(email);
        if (newPassword == null || newPassword.length() < 4) {
            throw new IllegalArgumentException("비밀번호는 4자 이상이어야 합니다.");
        }
        if (!emailVerificationService.isRecentlyVerified(email)) {
            throw new BusinessException("이메일 인증이 필요합니다. 인증 코드를 받아 입력해주세요.");
        }

        Member member = memberService.resetPassword(email, newPassword);
        emailVerificationService.consumeVerification(email);
        memberSessionService.expireSessions(member.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        session.invalidate();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/check-email")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkEmail(@RequestParam String email,
                                                          HttpServletRequest httpRequest) {
        if (!loginRateLimiter.tryAcquire(LoginRateLimiter.resolveClientIp(httpRequest))) {
            return tooManyRequests();
        }
        SecurityInputValidator.validateEmailOrThrow(email);
        Map<String, Object> result = new HashMap<>();
        boolean exists = memberService.findByEmail(email).isPresent();
        result.put("available", !exists);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Map<String, Object> result = new HashMap<>();

        if (userDetails != null) {
            Member member = userDetails.getMember();
            result.put("isLoggedIn", true);
            result.put("memberId", member.getId());
            result.put("nickname", member.getNickname());
            result.put("email", member.getEmail());
            result.put("role", member.getRole().name());
        } else {
            result.put("isLoggedIn", false);
        }

        return ResponseEntity.ok(result);
    }

    // 세션 유효성 검증 (Spring Security maximumSessions가 처리, 하위 호환용 유지)
    @GetMapping("/validate-session")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> validateSession(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Map<String, Object> result = new HashMap<>();

        if (userDetails == null) {
            result.put("valid", false);
            result.put("reason", "NOT_LOGGED_IN");
        } else {
            result.put("valid", true);
        }

        return ResponseEntity.ok(result);
    }
}
