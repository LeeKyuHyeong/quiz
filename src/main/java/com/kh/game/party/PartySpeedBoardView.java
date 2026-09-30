package com.kh.game.party;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 스피드퀴즈 보드(TV)가 폴링하는 상태.
 * word 는 턴이 진행 중이고 "보드 표시" 설정일 때만 실린다.
 */
public record PartySpeedBoardView(long version, String phase, String mode, String turnTeam,
                                  Integer limitSeconds, Integer remainingSeconds, LocalDateTime startedAt,
                                  String word, Map<String, TeamResult> results, String firstPick) {

    public record TeamResult(int correct, int pass, boolean done) {
    }
}
