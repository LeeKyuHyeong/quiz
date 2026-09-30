package com.kh.game.party;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 스피드퀴즈 보드(TV)가 폴링하는 상태.
 * word 는 턴이 진행 중이고 "보드 표시" 설정일 때만 실린다. limitSeconds 는 지금 턴 팀의 제한 시간,
 * limits 는 두 팀의 제한 시간(대기·결과 화면용).
 * phase·remainingSeconds·word 는 시간이 흐르면 조작 없이도(= 버전이 그대로여도) 바뀐다 —
 * 화면은 버전이 같다고 다시 그리기를 건너뛰면 안 된다.
 */
public record PartySpeedBoardView(long version, String phase, String mode, String turnTeam,
                                  Integer limitSeconds, Integer remainingSeconds, LocalDateTime startedAt,
                                  String word, Map<String, TeamResult> results, String firstPick,
                                  Map<String, Integer> limits) {

    public record TeamResult(int correct, int pass, boolean done) {
    }
}
