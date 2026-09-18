package com.kh.game.controller.client;

import com.kh.game.entity.Badge;
import com.kh.game.entity.Member;
import com.kh.game.entity.MemberBadge;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.BadgeService;
import com.kh.game.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/mypage")
@RequiredArgsConstructor
public class MyPageController {

    private final MemberService memberService;
    private final BadgeService badgeService;

    @GetMapping
    public String myPage(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId == null) {
            return "redirect:/auth/login?redirect=/mypage";
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member == null) {
            return "redirect:/auth/login?redirect=/mypage";
        }

        // 마이페이지 접속 시 기존 기록 기반 뱃지 자동 체크 (기존 사용자 지원)
        List<Badge> newlyAwarded = badgeService.checkAllBadgesForMember(member);
        if (!newlyAwarded.isEmpty()) {
            model.addAttribute("newlyAwardedBadges", newlyAwarded);
        }

        List<MemberBadge> memberBadges = badgeService.getMemberBadges(member);
        List<Badge> allBadges = badgeService.getAllActiveBadges();
        List<Long> ownedBadgeIds = badgeService.getMemberBadgeIds(member);

        model.addAttribute("member", member);
        model.addAttribute("memberBadges", memberBadges);
        model.addAttribute("allBadges", allBadges);
        model.addAttribute("ownedBadgeIds", ownedBadgeIds);
        model.addAttribute("badgeCount", badgeService.getBadgeCount(member));

        return "client/mypage";
    }

    @PostMapping("/badge/select")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> selectBadge(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Map<String, Object> result = new HashMap<>();
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;

        if (memberId == null) {
            result.put("success", false);
            result.put("message", "로그인이 필요합니다.");
            return ResponseEntity.ok(result);
        }

        Long badgeId = request.get("badgeId") != null
                ? Long.valueOf(request.get("badgeId").toString())
                : null;

        badgeService.selectBadge(memberId, badgeId);
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/badges/new")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getNewBadges(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;

        if (memberId == null) {
            return ResponseEntity.ok(List.of());
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member == null) {
            return ResponseEntity.ok(List.of());
        }

        List<MemberBadge> newBadges = badgeService.getNewBadges(member);
        List<Map<String, Object>> result = newBadges.stream()
                .map(mb -> {
                    Map<String, Object> badgeMap = new HashMap<>();
                    badgeMap.put("id", mb.getBadge().getId());
                    badgeMap.put("name", mb.getBadge().getName());
                    badgeMap.put("emoji", mb.getBadge().getEmoji());
                    badgeMap.put("description", mb.getBadge().getDescription());
                    badgeMap.put("rarity", mb.getBadge().getRarity().name());
                    badgeMap.put("rarityColor", mb.getBadge().getRarity().getColor());
                    return badgeMap;
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/badges/mark-read")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markBadgesAsRead(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Map<String, Object> result = new HashMap<>();
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;

        if (memberId == null) {
            result.put("success", false);
            return ResponseEntity.ok(result);
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member != null) {
            badgeService.markBadgesAsRead(member);
        }

        result.put("success", true);
        return ResponseEntity.ok(result);
    }
}
