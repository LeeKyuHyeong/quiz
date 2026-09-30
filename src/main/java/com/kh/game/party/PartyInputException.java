package com.kh.game.party;

/** 보낸 값 자체가 틀렸다(모르는 묶음·주제, 0 이하의 시간). 단계가 틀린 것(PartyGameException)과 구분해 400 으로 답한다. */
public class PartyInputException extends PartyGameException {

    public PartyInputException(String message) {
        super(message);
    }
}
