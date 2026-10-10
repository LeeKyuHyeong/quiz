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

    /** TV 보드. 보드 상태(/admin/party/state)만 읽으므로 정답은 공개 뒤에만 보인다. */
    @GetMapping("/board")
    public String board() {
        return "admin/party/board";
    }
}
