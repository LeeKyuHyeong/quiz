package com.kh.game.party;

import java.util.List;

/**
 * 스피드퀴즈 콘솔(MC)이 받는 상태. word 는 설정과 무관하게 진행 중이면 실린다.
 * log 는 이번 턴에 지나간 제시어와 정답(true)·패스(false) — 개수 시비가 붙으면 MC 가 본다.
 * 1초마다 폴링되므로 DB 를 읽지 않는 값만 담는다 — 주제별 남은 수는 PartySpeedQuizService#topics.
 */
public record PartySpeedConsoleView(PartySpeedBoardView board, String word, PartySpeedState.Setup setup,
                                    boolean canUndo, List<LogEntry> log) {

    public record LogEntry(String word, boolean correct) {
    }
}
