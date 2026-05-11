package com.kh.game.controller.client;

import com.kh.game.entity.Member;
import com.kh.game.exception.BusinessException;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.security.LoginRateLimiter;
import com.kh.game.service.EmailVerificationService;
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

    private ResponseEntity<Map<String, Object>> tooManyRequests() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect, Model model) {
        model.addAttribute("redirect", redirect);
        return "client/auth/login";
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
