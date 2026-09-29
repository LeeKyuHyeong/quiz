package com.kh.game.party;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 보드(TV)·플레이어 창이 폴링하는 상태. 정답은 reveal 에만, 그것도 정답 공개 단계에만 실린다.
 */
public record PartyBoardView(long version, String phase, int round,
                             Map<String, String> teamNames, Map<String, Integer> scores,
                             Item item, List<String> hints, String wrongTeam,
                             Reveal reveal, Player player, LocalDateTime timerStartedAt) {

    public record Item(String category, String subCategory, String presentation,
                       String videoId, Integer startTime, Integer duration,
                       String imageUrl, String questionText) {
    }

    public record Reveal(String answer, String detail, String source, String scoringTeam) {
    }

    public record Player(long seq, String cmd) {
    }
}
