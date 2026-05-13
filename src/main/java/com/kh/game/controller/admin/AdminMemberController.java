package com.kh.game.controller.admin;

import com.kh.game.entity.Member;
import com.kh.game.entity.MemberBadge;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.MemberBadgeRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Controller
@RequestMapping("/admin/member")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;
    private final MemberBadgeRepository memberBadgeRepository;
    private final SessionRegistry sessionRegistry;

    /**
     * 통합 회원 관리 페이지
     */
    @GetMapping({"", "/"})
    public String memberIndex(@RequestParam(defaultValue = "member") String tab, Model model) {
        model.addAttribute("activeTab", tab);
        model.addAttribute("menu", "member");

        // 회원 통계
        long totalCount = memberService.count();
        long activeCount = memberService.countByStatus(Member.MemberStatus.ACTIVE);
        long adminCount = memberService.countByRole(Member.MemberRole.ADMIN);

        model.addAttribute("totalCount", totalCount);
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("adminCount", adminCount);

        return "admin/member/index";
    }

    /**
     * AJAX 로딩용 회원 목록 콘텐츠 (fragment)
     */
    @GetMapping("/content")
    public String listContent(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String role,
                              @RequestParam(defaultValue = "id") String sort,
                              @RequestParam(defaultValue = "desc") String direction,
                              Model model) {
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
        Page<Member> memberPage;

        if (keyword != null && !keyword.trim().isEmpty()) {
            memberPage = memberService.search(keyword, pageable);
        } else if (status != null && !status.isEmpty()) {
            memberPage = memberService.findByStatus(Member.MemberStatus.valueOf(status), pageable);
        } else if (role != null && !role.isEmpty()) {
            memberPage = memberService.findByRole(Member.MemberRole.valueOf(role), pageable);
        } else {
            memberPage = memberService.findAll(pageable);
        }

        // 실시간 게임 수 집계 (N+1 방지 - 한 번의 쿼리로 조회)
        List<Long> memberIds = memberPage.getContent().stream()
                .map(Member::getId)
                .collect(Collectors.toList());
        Map<Long, Long> gameCountMap = memberService.getRealTimeGameCounts(memberIds);
        model.addAttribute("gameCountMap", gameCountMap);

        // 통계
        long totalCount = memberService.count();
        long activeCount = memberService.countByStatus(Member.MemberStatus.ACTIVE);
        long bannedCount = memberService.countByStatus(Member.MemberStatus.BANNED);
        long adminCount = memberService.countByRole(Member.MemberRole.ADMIN);

        model.addAttribute("members", memberPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("size", size);
        model.addAttribute("totalPages", memberPage.getTotalPages());
        model.addAttribute("totalItems", memberPage.getTotalElements());
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("bannedCount", bannedCount);
        model.addAttribute("adminCount", adminCount);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("role", role);
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);

        return "admin/member/fragments/member";
    }

    @GetMapping("/detail/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        return memberService.findById(id)
                .map(member -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", member.getId());
                    result.put("email", member.getEmail());
                    result.put("nickname", member.getNickname());
                    result.put("username", member.getUsername());
                    result.put("role", member.getRole().name());
                    result.put("status", member.getStatus().name());
                    // 실시간 게임 수 집계
                    Map<Long, Long> gameCountMap = memberService.getRealTimeGameCounts(List.of(id));
                    result.put("totalGames", gameCountMap.getOrDefault(id, 0L));
                    result.put("totalScore", member.getTotalScore());
                    result.put("accuracyRate", String.format("%.1f", member.getAccuracyRate()));
                    result.put("guessGames", member.getGuessGames());
                    result.put("guessScore", member.getGuessScore());
                    result.put("multiGames", member.getMultiGames());
                    result.put("multiScore", member.getMultiScore());
                    // 멀티게임 LP 티어 정보
                    result.put("multiTier", member.getMultiTier() != null ? member.getMultiTier().name() : "BRONZE");
                    result.put("multiTierDisplayName", member.getMultiTierDisplayName());
                    result.put("multiTierColor", member.getMultiTierColor());
                    result.put("multiLp", member.getMultiLp());
                    result.put("multiWins", member.getMultiWins());
                    result.put("multiTop3", member.getMultiTop3());
                    result.put("lastLoginAt", member.getLastLoginAt());
                    result.put("createdAt", member.getCreatedAt());

                    // 뱃지 목록 추가
                    List<MemberBadge> memberBadges = memberBadgeRepository.findByMemberWithBadge(member);
                    List<Map<String, Object>> badges = memberBadges.stream()
                            .map(mb -> {
                                Map<String, Object> badgeInfo = new HashMap<>();
                                badgeInfo.put("emoji", mb.getBadge().getEmoji());
                                badgeInfo.put("name", mb.getBadge().getName());
                                badgeInfo.put("description", mb.getBadge().getDescription());
                                badgeInfo.put("rarity", mb.getBadge().getRarity().name());
                                badgeInfo.put("rarityColor", mb.getBadge().getRarity().getColor());
                                badgeInfo.put("rarityName", mb.getBadge().getRarity().getDisplayName());
                                badgeInfo.put("category", mb.getBadge().getCategory().getDisplayName());
                                badgeInfo.put("earnedAt", mb.getEarnedAt());
                                return badgeInfo;
                            })
                            .collect(Collectors.toList());
                    result.put("badges", badges);
                    result.put("badgeCount", badges.size());

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/update-status/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id,
                                                            @RequestParam String status,
                                                            @AuthenticationPrincipal CustomUserDetails actor) {
        Map<String, Object> result = new HashMap<>();
        try {
            memberService.updateStatus(id, Member.MemberStatus.valueOf(status));
            log.info("Admin status change: actorId={}, targetId={}, newStatus={}",
                    actor.getMember().getId(), id, status);
            result.put("success", true);
            result.put("message", "상태가 변경되었습니다.");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "올바르지 않은 상태 값입니다.");
        } catch (Exception e) {
            log.error("회원 상태 변경 실패: targetId={}", id, e);
            result.put("success", false);
            result.put("message", "상태 변경 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/update-role/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable Long id,
                                                          @RequestParam String role,
                                                          @AuthenticationPrincipal CustomUserDetails actor) {
        Map<String, Object> result = new HashMap<>();
        try {
            memberService.updateRoleSafely(id, Member.MemberRole.valueOf(role), actor.getMember().getId());
            log.info("Admin role change: actorId={}, targetId={}, newRole={}",
                    actor.getMember().getId(), id, role);
            result.put("success", true);
            result.put("message", "권한이 변경되었습니다.");
        } catch (BusinessException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "올바르지 않은 권한 값입니다.");
        } catch (Exception e) {
            log.error("권한 변경 실패: targetId={}", id, e);
            result.put("success", false);
            result.put("message", "권한 변경 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reset-weekly/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetWeeklyStats(@PathVariable Long id,
                                                                @AuthenticationPrincipal CustomUserDetails actor) {
        Map<String, Object> result = new HashMap<>();
        try {
            memberService.resetWeeklyStats(id);
            log.info("Admin weekly stats reset: actorId={}, targetId={}",
                    actor.getMember().getId(), id);
            result.put("success", true);
            result.put("message", "주간 통계가 초기화되었습니다.");
        } catch (Exception e) {
            log.error("주간 통계 초기화 실패: targetId={}", id, e);
            result.put("success", false);
            result.put("message", "초기화 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reset-password/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                             @AuthenticationPrincipal CustomUserDetails actor) {
        Map<String, Object> result = new HashMap<>();
        try {
            memberService.resetPasswordToDefault(id);
            log.info("Admin password reset: actorId={}, targetId={}",
                    actor.getMember().getId(), id);
            result.put("success", true);
            result.put("message", "비밀번호가 초기화되었습니다. 해당 회원에게 비밀번호 찾기로 재설정하도록 안내해주세요.");
        } catch (BusinessException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("비밀번호 초기화 실패: targetId={}", id, e);
            result.put("success", false);
            result.put("message", "비밀번호 초기화 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/kick-session/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> kickSession(@PathVariable Long id,
                                                           @AuthenticationPrincipal CustomUserDetails actor) {
        Map<String, Object> result = new HashMap<>();
        try {
            // SessionRegistry에서 해당 사용자의 모든 세션 만료 처리
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
                    // CustomUserDetails에서 Member ID 매칭
                    if (principal instanceof com.kh.game.security.CustomUserDetails customDetails
                            && customDetails.getMember().getId().equals(id)) {
                        for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                            session.expireNow();
                        }
                    }
                }
            }
            log.info("Admin session kick: actorId={}, targetId={}",
                    actor.getMember().getId(), id);
            result.put("success", true);
            result.put("message", "세션이 강제 종료되었습니다.");
        } catch (Exception e) {
            log.error("세션 강제 종료 실패: targetId={}", id, e);
            result.put("success", false);
            result.put("message", "세션 종료 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }
}
