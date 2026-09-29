package com.kh.game.party;

/** 지금 단계에서 할 수 없는 조작. 메시지는 콘솔에 그대로 보여 준다. */
public class PartyGameException extends RuntimeException {

    public PartyGameException(String message) {
        super(message);
    }
}
