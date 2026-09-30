package com.kh.game.party;

/**
 * 조작을 보낸 화면이 본 버전이 지금 상태와 다르다 — 같은 버튼을 두 번 눌렀거나 다른 창에서 먼저 조작했다.
 * 조작은 적용하지 않는다.
 */
public class PartyStaleException extends PartyGameException {

    public PartyStaleException() {
        super("화면이 최신 상태가 아닙니다. 방금 누른 조작은 적용하지 않았습니다");
    }
}
