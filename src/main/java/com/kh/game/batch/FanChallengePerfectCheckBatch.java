package com.kh.game.batch;

import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.FanChallengeRecord;
import com.kh.game.repository.FanChallengeRecordRepository;
import com.kh.game.service.BatchService;
import com.kh.game.service.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 팬 챌린지 퍼펙트 검사 배치
 * 아티스트에 곡이 추가되면 해당 아티스트의 퍼펙트 클리어를 무효화합니다.
 * 마일스톤 뱃지는 회수하지 않음 (한번 획득하면 영구 보유)
 *
 * @deprecated 2026-09 폐지. 팬 챌린지가 고정 단계(20/25/30곡) 모델로 전환되면서 모든 기록이
 * stageLevel(1/2/3)을 갖게 되어, {@link #execute}의 {@code stageLevel >= 1} 가드(아래 66행)가
 * 전 레코드를 스킵 → 실질 무동작 배치가 됨(매일 04:00 SUCCESS만 남김).
 * 곡 수 변경에 따른 퍼펙트 무효화는 현행 {@link WeeklyPerfectRefreshBatch}가 담당한다.
 * 운영에서는 {@code batch_config.enabled=0}(관리자 배치 페이지 토글: {@code POST /admin/batch/toggle/BATCH_FAN_CHALLENGE_PERFECT_CHECK})로
 * 스케줄 등록에서 제외한다({@code BatchScheduler}가 enabled=1인 배치만 등록).
 * 이력 보존·롤백 가능성을 위해 클래스와 디스패치는 남겨둔다.
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class FanChallengePerfectCheckBatch {

    public static final String BATCH_ID = "BATCH_FAN_CHALLENGE_PERFECT_CHECK";

    private final FanChallengeRecordRepository fanChallengeRecordRepository;
    private final SongService songService;
    private final BatchService batchService;

    @Transactional
    public int execute(BatchExecutionHistory.ExecutionType executionType) {
        long startTime = System.currentTimeMillis();
        int invalidatedCount = 0;
        int checkedCount = 0;
        StringBuilder resultMessage = new StringBuilder();

        try {
            log.info("[{}] 배치 실행 시작 - 퍼펙트 클리어 유효성 검사", BATCH_ID);

            // 1. 현재 아티스트별 곡 수 조회 (캐시)
            List<Map<String, Object>> artistsWithCount = songService.getArtistsWithCount();
            Map<String, Integer> currentSongCounts = new java.util.HashMap<>();
            for (Map<String, Object> artistInfo : artistsWithCount) {
                String artist = (String) artistInfo.get("name");
                int count = ((Number) artistInfo.get("count")).intValue();
                currentSongCounts.put(artist, count);
            }
            log.info("현재 아티스트 수: {}개", currentSongCounts.size());

            // 2. 모든 퍼펙트 기록 조회
            List<FanChallengeRecord> perfectRecords = fanChallengeRecordRepository.findAllPerfectRecords();
            log.info("퍼펙트 기록 수: {}개", perfectRecords.size());

            // 3. 각 퍼펙트 기록 검사
            int skippedStageCount = 0;
            for (FanChallengeRecord record : perfectRecords) {
                checkedCount++;
                String artist = record.getArtist();
                Integer currentCount = currentSongCounts.get(artist);

                // 단계별 기록(1단계/2단계/3단계)은 고정 곡 수이므로 무효화 검사 스킵
                // 단계 기록: HARDCORE 모드 + stageLevel 존재 + totalSongs가 단계별 곡 수(20/25/30)
                if (record.getStageLevel() != null && record.getStageLevel() >= 1) {
                    log.debug("단계 기록 스킵: {} {}단계 (회원: {})",
                        artist, record.getStageLevel(), record.getMember().getNickname());
                    skippedStageCount++;
                    continue;
                }

                if (currentCount == null) {
                    // 아티스트가 더 이상 존재하지 않음 (모든 곡이 삭제됨)
                    log.debug("아티스트 곡 없음 - 퍼펙트 무효화: {} (회원: {})",
                        artist, record.getMember().getNickname());
                    record.setIsPerfectClear(false);
                    fanChallengeRecordRepository.save(record);
                    invalidatedCount++;
                } else if (record.getTotalSongs() < currentCount) {
                    // 곡이 추가되어 퍼펙트가 아님 (단계가 아닌 전체 챌린지만 해당)
                    log.debug("곡 추가로 퍼펙트 무효화: {} (기존: {}곡, 현재: {}곡, 회원: {})",
                        artist, record.getTotalSongs(), currentCount, record.getMember().getNickname());
                    record.setIsPerfectClear(false);
                    fanChallengeRecordRepository.save(record);
                    invalidatedCount++;
                }
            }
            log.info("단계 기록 스킵: {}건", skippedStageCount);

            resultMessage.append(String.format(
                "퍼펙트 검사 완료. 검사: %d건, 단계기록 스킵: %d건, 무효화: %d건",
                checkedCount, skippedStageCount, invalidatedCount
            ));

            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                BATCH_ID,
                executionType,
                BatchConfig.ExecutionResult.SUCCESS,
                resultMessage.toString(),
                invalidatedCount,
                executionTime
            );

            log.info("[{}] 배치 실행 완료 - 검사: {}건, 무효화: {}건, 소요시간: {}ms",
                BATCH_ID, checkedCount, invalidatedCount, executionTime);

            return invalidatedCount;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                BATCH_ID,
                executionType,
                BatchConfig.ExecutionResult.FAIL,
                "오류 발생: " + e.getMessage(),
                invalidatedCount,
                executionTime
            );

            log.error("[{}] 배치 실행 실패", BATCH_ID, e);
            throw new RuntimeException("배치 실행 실패: " + e.getMessage(), e);
        }
    }
}
