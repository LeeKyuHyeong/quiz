package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * 상태 1개를 들고, 바뀔 때마다 JSON 파일로 남긴다. 기동 때 파일이 있으면 이어서 진행한다.
 * 파일 경로가 비어 있으면 메모리에만 둔다(테스트·미설정 환경).
 */
@Slf4j
abstract class PartySnapshotHolder<T> {

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Class<T> type;
    private final Supplier<T> fresh;
    private T state;

    protected PartySnapshotHolder(ObjectMapper objectMapper, String stateFile, Class<T> type, Supplier<T> fresh) {
        this.objectMapper = objectMapper;
        this.file = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile);
        this.type = type;
        this.fresh = fresh;
        this.state = load();
    }

    public T get() {
        return state;
    }

    private T load() {
        if (file == null || !Files.exists(file)) {
            return fresh.get();
        }
        try {
            T loaded = objectMapper.readValue(file.toFile(), type);
            log.info("Party snapshot restored: file={}", file);
            return loaded;
        } catch (IOException e) {
            log.warn("Party snapshot unreadable, starting fresh: file={} cause={}", file, e.getMessage());
            return fresh.get();
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
            log.error("Party snapshot not saved: file={}", file, e);
        }
    }

    public void reset() {
        state = fresh.get();
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Party snapshot not deleted: file={}", file, e);
        }
    }
}
