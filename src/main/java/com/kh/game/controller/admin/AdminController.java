package com.kh.game.controller.admin;

import com.kh.game.entity.Member;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.security.LoginRateLimiter;
import com.kh.game.service.LoginAttemptService;
import com.kh.game.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Optional;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MemberService memberService;
    private final LoginRateLimiter loginRateLimiter;
    private final LoginAttemptService loginAttemptService;

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal CustomUserDetails userDetails) {
        // 이미 ADMIN으로 인증된 경우 바로 이동
        if (userDetails != null && userDetails.getRole() == Member.MemberRole.ADMIN) {
            return "redirect:/admin/song";
        }
        return "admin/login";
    }

    @PostMapping("/login-process")
    public String loginProcess(@RequestParam String username,
                               @RequestParam String password,
                               HttpServletRequest request,
                               Model model) {
        // 이 폼도 비밀번호를 검사한다 — /auth/login-process 와 같은 제한을 걸지 않으면 잠금을 피해 가는 경로가 된다
        String ipAddress = LoginRateLimiter.resolveClientIp(request);
        if (!loginRateLimiter.tryAcquire(ipAddress)) {
            model.addAttribute("error", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요.");
            return "admin/login";
        }
        Optional<Duration> locked = loginAttemptService.reserveAttempt(username);
        if (locked.isPresent()) {
            model.addAttribute("error", LoginAttemptService.lockedMessage(locked.get()));
            return "admin/login";
        }

        try {
            String userAgent = request.getHeader("User-Agent");

            Member member = memberService.login(username, password, ipAddress, userAgent);

            if (member.getRole() != Member.MemberRole.ADMIN) {
                model.addAttribute("error", "관리자 권한이 없습니다.");
                return "admin/login";
            }

            // 세션에는 아무것도 넣지 않는다. 인가는 Spring Security 의 ROLE_ADMIN 으로만 한다.
            // (전에는 Member 엔티티를 넣었다 — 비밀번호 해시가 세션에 실리고, 직렬화되지 않아 DB 세션 저장소에서 로그인이 실패한다)
            return "redirect:/admin/song";

        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "admin/login";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/admin/login";
    }
}
