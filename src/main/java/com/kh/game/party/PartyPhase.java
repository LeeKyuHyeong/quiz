package com.kh.game.party;

/**
 * 본게임 단계.
 * READY 는 MC 가 문제를 뽑았지만 아직 띄우지 않은 상태 — 보드에는 WAIT 로 보인다.
 */
public enum PartyPhase {
    WAIT, READY, SHOW, REVEAL, SCORES, END
}
