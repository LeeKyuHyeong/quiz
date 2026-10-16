package com.kh.game.batch;

import com.kh.game.config.CacheConfig;
import com.kh.game.entity.BatchAffectedSong;
import com.kh.game.entity.BatchAffectedSong.ActionType;
import com.kh.game.entity.BatchAffectedSong.AffectedReason;
import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Song;
import com.kh.game.repository.SongRepository;
import com.kh.game.service.BatchService;
import com.kh.game.service.YouTubeValidationService;
import com.kh.game.service.YouTubeValidationService.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * YouTube 영상 유효성 검사 배치
 *
 * 검증 로직:
 * - oEmbed API + 썸네일 이중 체크
 * - 둘 다 실패해야 비활성화
 *
 * 비활성화 사유:
 * - 삭제된 영상
 * - 임베드 불가 영상
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeVideoCheckBatch {

    private final SongRepository songRepository;
    private final BatchService batchService;
    private final YouTubeValidationService youTubeValidationService;

    public static final String BATCH_ID = "BATCH_YOUTUBE_VIDEO_CHECK";

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true) // 곡을 비활성화하므로 게임 설정 화면 집계를 비운다
    @Transactional
    public int execute(BatchExecutionHistory.ExecutionType executionType) {
        long startTime = System.currentTimeMillis();
        int totalChecked = 0;
        int deletedCount = 0;
        int embedDisabledCount = 0;
        StringBuilder resultMessage = new StringBuilder();
        BatchExecutionHistory history = null;

        try {
            log.info("[{}] 배치 실행 시작", BATCH_ID);

            // 배치 실행 이력 먼저 생성 (영향받은 곡 기록용)
            if (batchService != null && executionType != null) {
                history = batchService.createExecutionHistory(BATCH_ID, executionType);
            }

            // YouTube ID가 있는 활성 노래만 조회
            List<Song> songsWithYoutube = songRepository.findByUseYnAndYoutubeVideoIdIsNotNull("Y");
            totalChecked = songsWithYoutube.size();

            log.info("[{}] 검사 대상: {}곡", BATCH_ID, totalChecked);

            for (Song song : songsWithYoutube) {
                String videoId = song.getYoutubeVideoId();

                ValidationResult result = youTubeValidationService.validateVideo(videoId);

                if (!result.isValid()) {
                    song.setUseYn("N");
                    songRepository.save(song);

                    // 영향받은 곡 기록
                    AffectedReason reason;
                    String reasonDetail;

                    if (result.isEmbedDisabled()) {
                        embedDisabledCount++;
                        reason = AffectedReason.YOUTUBE_EMBED_DISABLED;
                        reasonDetail = "임베드 불가";
                        log.warn("임베드 불가로 비활성화: [ID:{}] {} - {} (videoId: {})",
                                song.getId(), song.getArtist(), song.getTitle(), videoId);
                    } else {
                        deletedCount++;
                        reason = AffectedReason.YOUTUBE_DELETED;
                        reasonDetail = String.format("oEmbed: %s, 썸네일: %s",
                                result.getOEmbedError(), result.getThumbnailError());
                        log.warn("삭제된 영상으로 비활성화: [ID:{}] {} - {} (videoId: {}) - {}",
                                song.getId(), song.getArtist(), song.getTitle(), videoId, reasonDetail);
                    }

                    // 영향받은 곡 기록 저장
                    if (history != null && batchService != null) {
                        batchService.recordAffectedSong(history, song, ActionType.DEACTIVATED, reason, reasonDetail);
                    }
                }
            }

            int totalDisabled = deletedCount + embedDisabledCount;

            // 결과 메시지 생성
            if (totalDisabled == 0) {
                resultMessage.append(String.format("전체 %d곡 검사 완료. 모든 영상 정상.", totalChecked));
            } else {
                resultMessage.append(String.format("전체 %d곡 중 %d곡 비활성화", totalChecked, totalDisabled));
                if (deletedCount > 0 || embedDisabledCount > 0) {
                    resultMessage.append(" (");
                    if (deletedCount > 0) {
                        resultMessage.append(String.format("삭제됨: %d", deletedCount));
                        if (embedDisabledCount > 0) {
                            resultMessage.append(", ");
                        }
                    }
                    if (embedDisabledCount > 0) {
                        resultMessage.append(String.format("임베드불가: %d", embedDisabledCount));
                    }
                    resultMessage.append(")");
                }
            }

            long executionTime = System.currentTimeMillis() - startTime;

            // 배치 실행 완료 기록
            if (history != null && batchService != null) {
                batchService.completeExecution(
                        history,
                        BatchConfig.ExecutionResult.SUCCESS,
                        resultMessage.toString().trim(),
                        totalDisabled,
                        executionTime
                );
            }

            log.info("[{}] 배치 실행 완료 - 검사: {}곡, 삭제됨: {}곡, 임베드불가: {}곡, 소요시간: {}ms",
                    BATCH_ID, totalChecked, deletedCount, embedDisabledCount, executionTime);

            return totalDisabled;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            if (history != null && batchService != null) {
                batchService.completeExecution(
                        history,
                        BatchConfig.ExecutionResult.FAIL,
                        "오류 발생: " + e.getMessage(),
                        deletedCount + embedDisabledCount,
                        executionTime
                );
            }

            log.error("[{}] 배치 실행 실패", BATCH_ID, e);
            throw new RuntimeException("배치 실행 실패: " + e.getMessage(), e);
        }
    }
}
