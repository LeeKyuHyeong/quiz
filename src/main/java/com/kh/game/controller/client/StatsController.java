package com.kh.game.controller.client;

import com.kh.game.entity.Member;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.MemberService;
import com.kh.game.service.WrongAnswerStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class StatsController {

    private final WrongAnswerStatsService wrongAnswerStatsService;
    private final MemberService memberService;

    @GetMapping("/stats")
    public String statsPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Member member = memberService.findLoginMember(userDetails);
        // 가장 어려운 곡 TOP 10
        List<Map<String, Object>> hardestSongs = wrongAnswerStatsService.getHardestSongs(5, 10);

        // 자주 틀리는 답변 TOP 10
        List<Map<String, Object>> commonWrongAnswers = wrongAnswerStatsService.getMostCommonWrongAnswers(10);

        model.addAttribute("hardestSongs", hardestSongs);
        model.addAttribute("commonWrongAnswers", commonWrongAnswers);
        model.addAttribute("member", member);

        // 로그인한 회원의 개인 통계
        if (member != null) {
            // 내가 가장 많이 맞춘 곡 TOP 10
            List<Map<String, Object>> myMostCorrect = wrongAnswerStatsService.getMemberMostCorrectSongs(member.getId(), 10);
            // 내가 가장 어려워하는 곡 TOP 10 (정답률 낮은 순)
            List<Map<String, Object>> myHardest = wrongAnswerStatsService.getMemberHardestSongs(member.getId(), 10);
            // 내가 플레이한 모든 곡 통계
            List<Map<String, Object>> myAllSongs = wrongAnswerStatsService.getMemberSongStats(member.getId(), 50);

            model.addAttribute("myMostCorrect", myMostCorrect);
            model.addAttribute("myHardest", myHardest);
            model.addAttribute("myAllSongs", myAllSongs);
        } else {
            model.addAttribute("myMostCorrect", Collections.emptyList());
            model.addAttribute("myHardest", Collections.emptyList());
            model.addAttribute("myAllSongs", Collections.emptyList());
        }

        return "client/stats";
    }
}
