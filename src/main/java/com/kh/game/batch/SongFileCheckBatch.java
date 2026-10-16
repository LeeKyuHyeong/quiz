package com.kh.game.batch;

import com.kh.game.config.CacheConfig;
import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Song;
import com.kh.game.repository.SongRepository;
import com.kh.game.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * MP3 파일 유효성 검사 배치
 * - 파일 존재 여부 확인
 * - MP3 형식 유효성 검사 (헤더 검증)
 * - 문제 있는 파일의 노래는 비활성화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SongFileCheckBatch {

    private final SongRepository songRepository;
    private final BatchService batchService;

    @Value("${file.upload-dir:uploads/songs}")
    private String uploadDir;

    public static final String BATCH_ID = "BATCH_SONG_FILE_CHECK";

    // MP3 관련 상수
    private static final int MIN_MP3_SIZE = 1024; // 최소 1KB
    private static final byte[] ID3V2_HEADER = {'I', 'D', '3'};
    private static final int MP3_SYNC_BYTE1 = 0xFF;
    private static final int MP3_SYNC_BYTE2_MASK = 0xE0; // 상위 3비트가 111이어야 함

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true) // 곡을 비활성화하므로 게임 설정 화면 집계를 비운다
    @Transactional
    public int execute(BatchExecutionHistory.ExecutionType executionType) {
        long startTime = System.currentTimeMillis();
        int totalChecked = 0;
        int missingCount = 0;
        int invalidCount = 0;
        StringBuilder resultMessage = new StringBuilder();

        try {
            log.info("[{}] 배치 실행 시작", BATCH_ID);

            List<Song> activeSongs = songRepository.findByUseYn("Y");
            totalChecked = activeSongs.size();

            for (Song song : activeSongs) {
                String filePath = song.getFilePath();
                if (filePath == null || filePath.isEmpty()) {
                    continue;
                }

                Path absolutePath = resolveFilePath(filePath);
                ValidationResult result = validateMp3File(absolutePath);

                if (!result.isValid()) {
                    song.setUseYn("N");

                    if (result.isMissing()) {
                        missingCount++;
                        log.warn("파일 누락으로 비활성화: [ID:{}] {} - {} ({})",
                                song.getId(), song.getArtist(), song.getTitle(), filePath);
                    } else {
                        invalidCount++;
                        log.warn("유효하지 않은 MP3로 비활성화: [ID:{}] {} - {} ({}) - 사유: {}",
                                song.getId(), song.getArtist(), song.getTitle(), filePath, result.getReason());
                    }
                }
            }

            int totalDisabled = missingCount + invalidCount;

            // 결과 메시지 생성
            if (totalDisabled == 0) {
                resultMessage.append(String.format("전체 %d곡 검사 완료. 모든 파일 정상.", totalChecked));
            } else {
                resultMessage.append(String.format("전체 %d곡 중 %d곡 비활성화 ", totalChecked, totalDisabled));
                if (missingCount > 0) {
                    resultMessage.append(String.format("(파일 누락: %d", missingCount));
                    if (invalidCount > 0) {
                        resultMessage.append(String.format(", 형식 오류: %d", invalidCount));
                    }
                    resultMessage.append(")");
                } else if (invalidCount > 0) {
                    resultMessage.append(String.format("(형식 오류: %d)", invalidCount));
                }
            }

            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                    BATCH_ID,
                    executionType,
                    BatchConfig.ExecutionResult.SUCCESS,
                    resultMessage.toString().trim(),
                    totalDisabled,
                    executionTime
            );

            log.info("[{}] 배치 실행 완료 - 검사: {}곡, 누락: {}곡, 형식오류: {}곡, 소요시간: {}ms",
                    BATCH_ID, totalChecked, missingCount, invalidCount, executionTime);

            return totalDisabled;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                    BATCH_ID,
                    executionType,
                    BatchConfig.ExecutionResult.FAIL,
                    "오류 발생: " + e.getMessage(),
                    missingCount + invalidCount,
                    executionTime
            );

            log.error("[{}] 배치 실행 실패", BATCH_ID, e);
            throw new RuntimeException("배치 실행 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파일 경로 처리
     */
    private Path resolveFilePath(String filePath) {
        if (filePath.startsWith("/") || filePath.startsWith("\\")) {
            return Paths.get(uploadDir, filePath);
        }
        // uploads/songs/ 경로가 이미 포함되어 있는지 확인
        if (filePath.startsWith("uploads/songs/") || filePath.startsWith("uploads\\songs\\")) {
            return Paths.get(filePath);
        }
        return Paths.get(uploadDir, filePath);
    }

    /**
     * MP3 파일 유효성 검사
     */
    private ValidationResult validateMp3File(Path filePath) {
        // 1. 파일 존재 확인
        if (!Files.exists(filePath)) {
            return ValidationResult.missing();
        }

        // 2. 파일 크기 확인
        try {
            long fileSize = Files.size(filePath);
            if (fileSize < MIN_MP3_SIZE) {
                return ValidationResult.invalid("파일 크기 너무 작음 (" + fileSize + " bytes)");
            }
        } catch (IOException e) {
            return ValidationResult.invalid("파일 크기 읽기 실패");
        }

        // 3. MP3 헤더 검증
        try {
            return validateMp3Header(filePath);
        } catch (IOException e) {
            return ValidationResult.invalid("파일 읽기 실패: " + e.getMessage());
        }
    }

    /**
     * MP3 헤더 검증
     * - ID3v2 태그 확인 또는
     * - MP3 프레임 동기 바이트 확인
     */
    private ValidationResult validateMp3Header(Path filePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            byte[] header = new byte[10];
            int bytesRead = raf.read(header);

            if (bytesRead < 3) {
                return ValidationResult.invalid("파일이 너무 작음");
            }

            // ID3v2 태그 확인
            if (header[0] == ID3V2_HEADER[0] &&
                header[1] == ID3V2_HEADER[1] &&
                header[2] == ID3V2_HEADER[2]) {

                // ID3v2 태그가 있으면 태그 크기를 계산하고 그 다음에 MP3 프레임이 있는지 확인
                if (bytesRead >= 10) {
                    int tagSize = ((header[6] & 0x7F) << 21) |
                                  ((header[7] & 0x7F) << 14) |
                                  ((header[8] & 0x7F) << 7) |
                                  (header[9] & 0x7F);

                    // ID3 태그 다음 위치로 이동해서 MP3 프레임 확인
                    long framePosition = 10 + tagSize;
                    if (raf.length() > framePosition + 2) {
                        raf.seek(framePosition);
                        int b1 = raf.read();
                        int b2 = raf.read();

                        if (b1 == MP3_SYNC_BYTE1 && (b2 & MP3_SYNC_BYTE2_MASK) == MP3_SYNC_BYTE2_MASK) {
                            return ValidationResult.valid();
                        }
                    }
                }
                // ID3 태그만 있어도 일단 유효한 것으로 처리
                return ValidationResult.valid();
            }

            // ID3 태그 없이 바로 MP3 프레임으로 시작하는 경우
            if ((header[0] & 0xFF) == MP3_SYNC_BYTE1 &&
                ((header[1] & 0xFF) & MP3_SYNC_BYTE2_MASK) == MP3_SYNC_BYTE2_MASK) {
                return ValidationResult.valid();
            }

            // 파일 내에서 MP3 프레임 동기 바이트 찾기 (처음 8KB 내에서)
            raf.seek(0);
            byte[] buffer = new byte[8192];
            bytesRead = raf.read(buffer);

            for (int i = 0; i < bytesRead - 1; i++) {
                if ((buffer[i] & 0xFF) == MP3_SYNC_BYTE1 &&
                    ((buffer[i + 1] & 0xFF) & MP3_SYNC_BYTE2_MASK) == MP3_SYNC_BYTE2_MASK) {
                    return ValidationResult.valid();
                }
            }

            return ValidationResult.invalid("MP3 형식이 아님");
        }
    }

    /**
     * 검증 결과 클래스
     */
    private static class ValidationResult {
        private final boolean valid;
        private final boolean missing;
        private final String reason;

        private ValidationResult(boolean valid, boolean missing, String reason) {
            this.valid = valid;
            this.missing = missing;
            this.reason = reason;
        }

        static ValidationResult valid() {
            return new ValidationResult(true, false, null);
        }

        static ValidationResult missing() {
            return new ValidationResult(false, true, "파일 없음");
        }

        static ValidationResult invalid(String reason) {
            return new ValidationResult(false, false, reason);
        }

        boolean isValid() { return valid; }
        boolean isMissing() { return missing; }
        String getReason() { return reason; }
    }
}
