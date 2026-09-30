package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * 상태 1개를 들고, 바뀔 때마다 JSON 파일로 남긴다. 기동 때 파일이 있으면 이어서 진행한다.
 * 파일 경로가 비어 있으면 메모리에만 둔다(테스트·미설정 환경).
 *
 * 파일을 잃지 않기 위한 규칙:
 * 저장은 .tmp 에 쓰고 한 번에 바꿔치기한다 · 기동 때 본 파일과 .tmp 중 버전이 높은 쪽으로 이어간다 ·
 * 읽을 수 없는 파일은 덮어쓰지 않고 .bad-시각 으로 옮긴다 · 비울 때는 지우지 않고 .bak-버전 으로 옮긴다.
 */
@Slf4j
abstract class PartySnapshotHolder<T extends PartyVersioned> {

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Class<T> type;
    private final Supplier<T> fresh;
    private T state;

    protected PartySnapshotHolder(ObjectMapper objectMapper, String stateFile, Class<T> type, Supplier<T> fresh) {
        this.objectMapper = objectMapper;
        this.file = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile).toAbsolutePath().normalize();
        this.type = type;
        this.fresh = fresh;
        this.state = load();
    }

    public T get() {
        return state;
    }

    private Path sibling(String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    private T load() {
        if (file == null) {
            return fresh(0);
        }
        // 저장 도중 꺼지면 본 파일 없이 .tmp 만 남고, 바꿔치기가 실패했으면 .tmp 가 본 파일보다 새것이다
        T main = read(file);
        T temp = read(sibling(".tmp"));
        T restored = main == null ? temp
                : temp == null || temp.getVersion() <= main.getVersion() ? main : temp;
        if (restored == null) {
            log.info("Party snapshot: none, starting fresh: file={}", file);
            return fresh(0);
        }
        // 꺼지기 전 마지막 저장이 실패했다면, 열려 있던 화면은 같은 번호로 다른 내용을 이미 봤을 수 있다
        restored.setVersion(Math.max(System.currentTimeMillis(), restored.getVersion() + 1));
        return restored;
    }

    /** 없거나 읽을 수 없으면 null. 읽을 수 없는 파일은 다음 저장이 덮어쓰지 않게 옆으로 옮겨 둔다. */
    private T read(Path source) {
        if (!Files.exists(source)) {
            return null;
        }
        try {
            T loaded = objectMapper.readValue(source.toFile(), type);
            log.info("Party snapshot restored: file={}", source);
            return loaded;
        } catch (IOException e) {
            Path kept = source.resolveSibling(source.getFileName() + ".bad-" + System.currentTimeMillis());
            log.warn("Party snapshot unreadable, kept aside as {}: cause={}", kept.getFileName(), e.getMessage());
            try {
                Files.move(source, kept);
            } catch (IOException moveFailure) {
                log.error("Party snapshot could not be kept aside: file={}", source, moveFailure);
            }
            return null;
        }
    }

    /**
     * 새 상태의 버전은 0 이 아니라 현재 시각에서 시작하고, 앞 상태의 버전보다 항상 크다.
     * 새 게임·초기화·재시작 뒤에 열려 있던 보드가 "같은 버전"으로 착각해 화면을 건너뛰지 않게 한다.
     */
    private T fresh(long previousVersion) {
        T created = fresh.get();
        created.setVersion(Math.max(System.currentTimeMillis(), previousVersion + 1));
        return created;
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
            Files.createDirectories(file.getParent());
            Path temp = sibling(".tmp");
            objectMapper.writeValue(temp.toFile(), state);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Party snapshot not saved: file={}", file, e);
        }
    }

    /**
     * 새 상태로 바꾼다. 앞 상태의 파일은 지우지 않고 .bak-버전 으로 남긴다(잘못 누른 새 게임·초기화 복구용).
     * 버전을 붙여 여러 개를 남긴다 — 한 칸이면 잘못 누른 뒤 한 번 더 누를 때 진짜 백업이 덮인다.
     */
    public void reset() {
        long previousVersion = state.getVersion();
        state = fresh(previousVersion);
        if (file == null) {
            return;
        }
        try {
            if (Files.exists(file)) {
                Files.move(file, sibling(".bak-" + previousVersion), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Party snapshot not moved to backup: file={}", file, e);
        }
    }
}
