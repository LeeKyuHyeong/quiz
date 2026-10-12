package com.kh.game.party;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 파티 화면(HTML). 상태·조작은 화면의 스크립트가 PartyController 의 JSON 경로로 한다.
 */
@Controller
@RequestMapping("/admin/party")
public class PartyPageController {

    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("menu", "party");
        return "admin/party/console";
    }

    /** 문제 관리(TSV 올리기 · 현황 · 목록 · 숨기기). 정답이 보이는 화면이라 TV 에 띄우지 않는다. */
    @GetMapping("/content")
    public String content(Model model) {
        model.addAttribute("menu", "party");
        return "admin/party/content";
    }

    /**
     * 플레이어 창 — 휴대폰을 콘솔로 쓸 때 노트북에서 소리를 내는 창. 보드 상태만 읽고(정답 없음)
     * 서버의 재생 명령(seq)을 따라간다.
     */
    @GetMapping("/player")
    public String player() {
        return "admin/party/player";
    }

    /** 스피드퀴즈 콘솔(MC). 상태·조작은 /admin/party/speed/** JSON 경로(PartySpeedController). */
    @GetMapping("/speed")
    public String speedConsole(Model model) {
        model.addAttribute("menu", "party");
        return "admin/party/speed-console";
    }

    /** 스피드퀴즈 TV 보드. 제시어는 "보드 표시" 설정일 때만 실린다(맞히는 사람은 등을 돌린다). */
    @GetMapping("/speed/board")
    public String speedBoard() {
        return "admin/party/speed-board";
    }

    /** TV 보드. 보드 상태(/admin/party/state)만 읽으므로 정답은 공개 뒤에만 보인다. */
    @GetMapping("/board")
    public String board() {
        return "admin/party/board";
    }
}
