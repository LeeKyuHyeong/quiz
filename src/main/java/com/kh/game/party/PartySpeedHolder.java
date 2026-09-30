package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 스피드퀴즈 상태 1개. 본게임 상태와 서로 참조하지 않는다. 파일은 party.speed-state-file. */
@Component
public class PartySpeedHolder extends PartySnapshotHolder<PartySpeedState> {

    public PartySpeedHolder(ObjectMapper objectMapper, @Value("${party.speed-state-file:}") String stateFile) {
        super(objectMapper, stateFile, PartySpeedState.class, PartySpeedState::new);
    }
}
