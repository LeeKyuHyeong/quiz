package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 본게임 상태 1개. 파일은 party.state-file. */
@Component
public class PartyGameHolder extends PartySnapshotHolder<PartyGameState> {

    public PartyGameHolder(ObjectMapper objectMapper, @Value("${party.state-file:}") String stateFile) {
        super(objectMapper, stateFile, PartyGameState.class, PartyGameState::new);
    }
}
