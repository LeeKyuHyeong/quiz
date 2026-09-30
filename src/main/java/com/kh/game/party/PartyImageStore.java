package com.kh.game.party;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 사진·캡처 폴더(party.image-dir, 저장소 밖). 파일은 git·서버에 올리지 않는다.
 * 폴더가 설정되지 않았으면 어떤 사진도 없는 것으로 본다.
 */
@Slf4j
@Component
public class PartyImageStore {

    /** 경로 구분자와 윈도우에서 파일 이름에 쓸 수 없는 글자. */
    private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final Path dir;

    public PartyImageStore(@Value("${party.image-dir:}") String imageDir) {
        this.dir = imageDir == null || imageDir.isBlank() ? null : Path.of(imageDir).toAbsolutePath().normalize();
        if (dir != null && !Files.isDirectory(dir)) {
            // 실행 위치가 달라지면 상대 경로가 엉뚱한 곳을 가리키고, 사진 문제가 조용히 전부 빠진다
            log.warn("Party image dir does not exist — no image question is playable: dir={}", dir);
        } else if (dir != null) {
            log.info("Party image dir: {}", dir);
        }
    }

    /** 폴더 바로 아래에 둘 수 있는 파일 이름인가(경로 구분자·상위 폴더 표기·윈도우 금지 글자 거부). */
    public static boolean isSafeName(String fileName) {
        return fileName != null && !fileName.isBlank() && !fileName.contains("..")
                && !UNSAFE.matcher(fileName).find();
    }

    public boolean exists(String fileName) {
        if (dir == null || !isSafeName(fileName)) {
            return false;
        }
        try {
            return Files.isRegularFile(dir.resolve(fileName));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** 설정되지 않았으면 null. */
    public Path directory() {
        return dir;
    }
}
