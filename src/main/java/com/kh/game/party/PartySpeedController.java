package com.kh.game.party;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 스피드퀴즈 경로. 본게임과 같은 규칙: 조작(POST)은 화면이 본 버전을 보내고 바뀐 콘솔 상태를 돌려받는다.
 */
@RestController
@RequestMapping("/admin/party/speed")
@RequiredArgsConstructor
public class PartySpeedController {

    private final PartySpeedQuizService speedService;

    @GetMapping("/state")
    public PartySpeedBoardView state() {
        return speedService.boardView();
    }

    @GetMapping("/console/state")
    public PartySpeedConsoleView consoleState() {
        return speedService.consoleView();
    }

    /** 주제 → 남은 제시어 수. DB 를 읽으므로 폴링하지 않는다. */
    @GetMapping("/topics")
    public Map<String, Integer> topics() {
        return speedService.topics();
    }

    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestParam long version,
                                     @RequestParam(required = false) List<String> topic,
                                     @RequestParam int limitA, @RequestParam int limitB,
                                     @RequestParam PartySpeedMode mode,
                                     @RequestParam boolean wordOnBoard) {
        return act(version, () -> speedService.configure(topic, limitA, limitB, mode, wordOnBoard));
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam long version, @RequestParam PartyTeam team) {
        return act(version, () -> speedService.start(team));
    }

    @PostMapping("/correct")
    public Map<String, Object> correct(@RequestParam long version) {
        return act(version, speedService::correct);
    }

    @PostMapping("/pass")
    public Map<String, Object> pass(@RequestParam long version) {
        return act(version, speedService::pass);
    }

    @PostMapping("/undo")
    public Map<String, Object> undo(@RequestParam long version) {
        return act(version, speedService::undo);
    }

    @PostMapping("/finish")
    public Map<String, Object> finish(@RequestParam long version) {
        return act(version, speedService::finish);
    }

    @PostMapping("/rematch")
    public Map<String, Object> rematch(@RequestParam long version) {
        return act(version, speedService::rematch);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam long version) {
        return act(version, speedService::reset);
    }

    private Map<String, Object> act(long version, Runnable action) {
        PartySpeedConsoleView state = speedService.apply(version, action);
        return Map.of("success", true, "state", state, "topics", speedService.topics());
    }
}
