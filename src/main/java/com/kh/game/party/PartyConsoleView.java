package com.kh.game.party;

import java.util.List;

/**
 * 콘솔(MC)이 받는 상태. 문제를 뽑은 순간부터 정답 카드가 실린다.
 * phase 는 실제 단계(READY 포함), board 는 TV 에 보이는 그대로.
 * item 은 뽑은 순간부터 실린다(보드에는 띄운 뒤에만) — 콘솔이 띄우기 전에 영상·사진을 미리 확인할 수 있게.
 * 1초마다 폴링되므로 DB 를 읽지 않는 값만 담는다 — 남은 문제 수는 PartyGameService#remaining.
 */
public record PartyConsoleView(String phase, PartyBoardView board, Card card, PartyBoardView.Item item,
                               List<PartyGameState.HistoryEntry> history) {

    /** difficulty 는 1(하)·2(중)·3(상), 없으면 null. */
    public record Card(String category, String subCategory, String presentation,
                       String answer, String aliases, String detail, String source,
                       List<String> hints, int hintsOpened, Integer difficulty) {
    }
}
