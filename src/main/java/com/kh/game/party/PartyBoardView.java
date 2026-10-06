package com.kh.game.party;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 보드(TV)·플레이어 창이 폴링하는 상태. 정답은 reveal 에만, 그것도 정답 공개 단계에만 실린다.
 * timerElapsedSeconds 는 서버가 잰 경과 시간 — 콘솔이 다른 기기(휴대폰)여도 시계 차이와 무관하게 쓴다.
 */
public record PartyBoardView(long version, String phase, int round,
                             Map<String, String> teamNames, Map<String, Integer> scores,
                             Item item, List<String> hints, String wrongTeam, boolean freeChallenge,
                             Reveal reveal, Player player, LocalDateTime timerStartedAt,
                             Integer timerElapsedSeconds) {

    public record Item(String category, String subCategory, String presentation,
                       String videoId, Integer startTime, Integer duration,
                       String imageUrl, String questionText) {
    }

    public record Reveal(String answer, String detail, String source, String scoringTeam) {
    }

    public record Player(long seq, String cmd) {
    }
}
