package com.kh.game.party;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 파티 본게임 경로. /admin/** 라 관리자만 들어온다(SecurityConfig 무변경).
 * 조작(POST)은 화면이 본 버전(version)을 함께 보내고, 성공하면 바뀐 콘솔 상태를 돌려받는다.
 */
@RestController
@RequestMapping("/admin/party")
@RequiredArgsConstructor
public class PartyController {

    private final PartyGameService gameService;

    // ---------- 조회 (보드·플레이어 창·콘솔이 1초마다 부른다) ----------

    @GetMapping("/state")
    public PartyBoardView state() {
        return gameService.boardView();
    }

    @GetMapping("/console/state")
    public PartyConsoleView consoleState() {
        return gameService.consoleView();
    }

    /** 대분류 → 중분류 → 남은 문제 수. DB 를 읽으므로 폴링하지 않는다. */
    @GetMapping("/remaining")
    public Map<String, Map<String, Integer>> remaining() {
        return gameService.remaining();
    }

    // ---------- 조작 ----------

    @PostMapping("/pick")
    public Map<String, Object> pick(@RequestParam long version, @RequestParam String category,
                                    @RequestParam(required = false) String subCategory) {
        if (PartyGameService.SONG.equals(category)) {
            return act(version, () -> gameService.pickSong(subCategory));
        }
        PartyCategory parsed = parseCategory(category);
        return act(version, () -> gameService.pick(parsed, subCategory));
    }

    @PostMapping("/show")
    public Map<String, Object> show(@RequestParam long version) {
        return act(version, gameService::show);
    }

    @PostMapping("/play")
    public Map<String, Object> play(@RequestParam long version) {
        return act(version, gameService::play);
    }

    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestParam long version) {
        return act(version, gameService::pause);
    }

    @PostMapping("/restart")
    public Map<String, Object> restart(@RequestParam long version) {
        return act(version, gameService::restart);
    }

    @PostMapping("/hint")
    public Map<String, Object> hint(@RequestParam long version) {
        return act(version, gameService::openHint);
    }

    @PostMapping("/wrong")
    public Map<String, Object> wrong(@RequestParam long version, @RequestParam PartyTeam team) {
        return act(version, () -> gameService.wrong(team));
    }

    @PostMapping("/correct")
    public Map<String, Object> correct(@RequestParam long version, @RequestParam PartyTeam team) {
        return act(version, () -> gameService.correct(team));
    }

    @PostMapping("/miss")
    public Map<String, Object> miss(@RequestParam long version) {
        return act(version, gameService::miss);
    }

    @PostMapping("/score")
    public Map<String, Object> score(@RequestParam long version, @RequestParam PartyTeam team,
                                     @RequestParam int delta) {
        return act(version, () -> gameService.adjustScore(team, delta));
    }

    @PostMapping("/next")
    public Map<String, Object> next(@RequestParam long version) {
        return act(version, gameService::next);
    }

    @PostMapping("/scores")
    public Map<String, Object> scores(@RequestParam long version) {
        return act(version, gameService::showScores);
    }

    @PostMapping("/wait")
    public Map<String, Object> backToWait(@RequestParam long version) {
        return act(version, gameService::backToWait);
    }

    /** keepUsed=true 는 다음 판(이미 낸 문제는 다시 안 나옴), false 는 전부 비움. 빠지면 거부한다. */
    @PostMapping("/new")
    public Map<String, Object> newGame(@RequestParam long version, @RequestParam boolean keepUsed) {
        return act(version, () -> gameService.newGame(keepUsed));
    }

    /** 띄운 문제를 정답 공개 없이 거둔다(죽은 영상·깨진 사진). */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam long version) {
        return act(version, gameService::cancelShow);
    }

    @PostMapping("/end")
    public Map<String, Object> end(@RequestParam long version) {
        return act(version, gameService::end);
    }

    private Map<String, Object> act(long version, Runnable action) {
        PartyConsoleView state = gameService.apply(version, action);
        return Map.of("success", true, "state", state, "remaining", gameService.remaining());
    }

    private static PartyCategory parseCategory(String category) {
        try {
            return PartyCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new PartyInputException("대분류를 알 수 없습니다: '" + category + "'");
        }
    }
}
