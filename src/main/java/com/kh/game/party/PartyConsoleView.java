package com.kh.game.party;

import java.util.List;
import java.util.Map;

/**
 * 콘솔(MC)이 받는 상태. 문제를 뽑은 순간부터 정답 카드가 실린다.
 * phase 는 실제 단계(READY 포함), board 는 TV 에 보이는 그대로.
 */
public record PartyConsoleView(String phase, PartyBoardView board, Card card,
                               Map<String, Map<String, Integer>> remaining,
                               List<PartyGameState.HistoryEntry> history) {

    public record Card(String category, String subCategory, String presentation,
                       String answer, String aliases, String detail, String source,
                       List<String> hints, int hintsOpened) {
    }
}
