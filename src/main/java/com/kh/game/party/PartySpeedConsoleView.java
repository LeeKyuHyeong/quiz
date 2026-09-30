package com.kh.game.party;

import java.util.Map;

/**
 * 스피드퀴즈 콘솔(MC)이 받는 상태. word 는 설정과 무관하게 진행 중이면 실린다.
 * topics 는 주제 → 남은 제시어 수('전체' 포함).
 */
public record PartySpeedConsoleView(PartySpeedBoardView board, String word, PartySpeedState.Setup setup,
                                    Map<String, Integer> topics, boolean canUndo) {
}
