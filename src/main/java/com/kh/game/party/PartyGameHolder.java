package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 본게임 상태 1개를 들고, 바뀔 때마다 파일로 남긴다. 기동 때 파일이 있으면 이어서 진행한다.
 * party.state-file 이 비어 있으면 메모리에만 둔다(테스트·미설정 환경).
 */
@Slf4j
@Component
public class PartyGameHolder {

    private final ObjectMapper objectMapper;
    private final Path file;
    private PartyGameState state;

    public PartyGameHolder(ObjectMapper objectMapper, @Value("${party.state-file:}") String stateFile) {
        this.objectMapper = objectMapper;
        this.file = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile);
        this.state = load();
    }

    public PartyGameState get() {
        return state;
    }

    private PartyGameState load() {
        if (file == null || !Files.exists(file)) {
            return new PartyGameState();
        }
        try {
            PartyGameState loaded = objectMapper.readValue(file.toFile(), PartyGameState.class);
            log.info("Party game restored: file={} round={} phase={}", file, loaded.getRound(), loaded.getPhase());
            return loaded;
        } catch (IOException e) {
            log.warn("Party game snapshot unreadable, starting a new game: file={} cause={}", file, e.getMessage());
            return new PartyGameState();
        }
    }

    /**
     * 저장에 실패해도 진행은 막지 않는다 — 행사 중 디스크 문제로 게임이 멈추는 쪽이 더 나쁘다.
     * 대신 재시작 복구가 안 되므로 ERROR 로 남긴다.
     */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = parent.resolve(file.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), state);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Party game snapshot not saved: file={}", file, e);
        }
    }

    public void reset() {
        state = new PartyGameState();
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Party game snapshot not deleted: file={}", file, e);
        }
    }
}
