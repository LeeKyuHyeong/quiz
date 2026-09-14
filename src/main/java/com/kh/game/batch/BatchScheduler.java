package com.kh.game.batch;

import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class BatchScheduler {

    private final TaskScheduler taskScheduler;
    private final BatchService batchService;
    private final SessionCleanupBatch sessionCleanupBatch;
    private final RoomCleanupBatch roomCleanupBatch;
    private final ChatCleanupBatch chatCleanupBatch;
    private final DailyStatsBatch dailyStatsBatch;
    private final LoginHistoryCleanupBatch loginHistoryCleanupBatch;
    private final InactiveMemberBatch inactiveMemberBatch;
    private final SongFileCheckBatch songFileCheckBatch;
    private final SongAnalyticsBatch songAnalyticsBatch;
    private final YouTubeVideoCheckBatch youTubeVideoCheckBatch;
    private final SystemReportBatch systemReportBatch;
    private final WeeklyRankingResetBatch weeklyRankingResetBatch;
    private final MonthlyRankingResetBatch monthlyRankingResetBatch;
    private final BoardCleanupBatch boardCleanupBatch;
    private final BatchExecutionHistoryCleanupBatch batchExecutionHistoryCleanupBatch;
    private final GameRoundAttemptCleanupBatch gameRoundAttemptCleanupBatch;
    private final DuplicateSongCheckBatch duplicateSongCheckBatch;
    private final GameSessionCleanupBatch gameSessionCleanupBatch;
    private final SongReportCleanupBatch songReportCleanupBatch;
    private final BadgeAwardBatch badgeAwardBatch;
    private final RankingSnapshotBatch rankingSnapshotBatch;
    private final WeeklyPerfectRefreshBatch weeklyPerfectRefreshBatch;
    private final LpDecayBatch lpDecayBatch;
    private final SongAnswerGenerationBatch songAnswerGenerationBatch;
    private final LoginStreakBatch loginStreakBatch;

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("애플리케이션 시작 완료 - 배치 스케줄 초기화");
        refreshAllSchedules();
    }

    public void refreshAllSchedules() {
        log.info("배치 스케줄 새로고침 시작");

        scheduledTasks.forEach((id, future) -> {
            future.cancel(false);
            log.debug("기존 스케줄 취소: {}", id);
        });
        scheduledTasks.clear();

        batchService.findEnabledAndImplemented().forEach(this::scheduleTask);

        log.info("배치 스케줄 새로고침 완료: {}개 등록", scheduledTasks.size());
    }

    public void refreshSchedule(String batchId) {
        ScheduledFuture<?> existing = scheduledTasks.remove(batchId);
        if (existing != null) {
            existing.cancel(false);
            log.debug("기존 스케줄 취소: {}", batchId);
        }

        batchService.findById(batchId).ifPresent(config -> {
            if (config.getImplemented() && config.getEnabled()) {
                scheduleTask(config);
            }
        });
    }

    private void scheduleTask(BatchConfig config) {
        String batchId = config.getBatchId();
        String cronExpression = config.getCronExpression();

        try {
            Runnable task = createTask(batchId);
            if (task == null) {
                log.warn("배치 작업을 찾을 수 없음: {}", batchId);
                return;
            }

            ScheduledFuture<?> future = taskScheduler.schedule(
                    task,
                    new CronTrigger(cronExpression)
            );

            scheduledTasks.put(batchId, future);
            log.info("배치 스케줄 등록: {} - {}", batchId, cronExpression);

        } catch (Exception e) {
            log.error("배치 스케줄 등록 실패: {} - {}", batchId, e.getMessage());
        }
    }

    private Runnable createTask(String batchId) {
        switch (batchId) {
            case SessionCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        sessionCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case RoomCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        roomCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case ChatCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        chatCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case DailyStatsBatch.BATCH_ID:
                return () -> {
                    try {
                        dailyStatsBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case LoginHistoryCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        loginHistoryCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case InactiveMemberBatch.BATCH_ID:
                return () -> {
                    try {
                        inactiveMemberBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case SongFileCheckBatch.BATCH_ID:
                return () -> {
                    try {
                        songFileCheckBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case SongAnalyticsBatch.BATCH_ID:
                return () -> {
                    try {
                        songAnalyticsBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case YouTubeVideoCheckBatch.BATCH_ID:
                return () -> {
                    try {
                        youTubeVideoCheckBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case SystemReportBatch.BATCH_ID:
                return () -> {
                    try {
                        systemReportBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case WeeklyRankingResetBatch.BATCH_ID:
                return () -> {
                    try {
                        weeklyRankingResetBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case MonthlyRankingResetBatch.BATCH_ID:
                return () -> {
                    try {
                        monthlyRankingResetBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case BoardCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        boardCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case BatchExecutionHistoryCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        batchExecutionHistoryCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case GameRoundAttemptCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        gameRoundAttemptCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case DuplicateSongCheckBatch.BATCH_ID:
                return () -> {
                    try {
                        duplicateSongCheckBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case GameSessionCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        gameSessionCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case SongReportCleanupBatch.BATCH_ID:
                return () -> {
                    try {
                        songReportCleanupBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case BadgeAwardBatch.BATCH_ID:
                return () -> {
                    try {
                        badgeAwardBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case RankingSnapshotBatch.BATCH_ID:
                return () -> {
                    try {
                        rankingSnapshotBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case WeeklyPerfectRefreshBatch.BATCH_ID:
                return () -> {
                    try {
                        weeklyPerfectRefreshBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case LpDecayBatch.BATCH_ID:
                return () -> {
                    try {
                        lpDecayBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case SongAnswerGenerationBatch.BATCH_ID:
                return () -> {
                    try {
                        songAnswerGenerationBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            case LoginStreakBatch.BATCH_ID:
                return () -> {
                    try {
                        loginStreakBatch.execute(BatchExecutionHistory.ExecutionType.SCHEDULED);
                    } catch (Exception e) {
                        log.error("배치 실행 중 오류: {}", batchId, e);
                    }
                };
            default:
                return null;
        }
    }

    public void executeManually(String batchId) {
        log.info("배치 수동 실행 요청: {}", batchId);

        switch (batchId) {
            case SessionCleanupBatch.BATCH_ID:
                sessionCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case RoomCleanupBatch.BATCH_ID:
                roomCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case ChatCleanupBatch.BATCH_ID:
                chatCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case DailyStatsBatch.BATCH_ID:
                dailyStatsBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case LoginHistoryCleanupBatch.BATCH_ID:
                loginHistoryCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case InactiveMemberBatch.BATCH_ID:
                inactiveMemberBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case SongFileCheckBatch.BATCH_ID:
                songFileCheckBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case SongAnalyticsBatch.BATCH_ID:
                songAnalyticsBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case YouTubeVideoCheckBatch.BATCH_ID:
                youTubeVideoCheckBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case SystemReportBatch.BATCH_ID:
                systemReportBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case WeeklyRankingResetBatch.BATCH_ID:
                weeklyRankingResetBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case MonthlyRankingResetBatch.BATCH_ID:
                monthlyRankingResetBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case BoardCleanupBatch.BATCH_ID:
                boardCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case BatchExecutionHistoryCleanupBatch.BATCH_ID:
                batchExecutionHistoryCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case GameRoundAttemptCleanupBatch.BATCH_ID:
                gameRoundAttemptCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case DuplicateSongCheckBatch.BATCH_ID:
                duplicateSongCheckBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case GameSessionCleanupBatch.BATCH_ID:
                gameSessionCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case SongReportCleanupBatch.BATCH_ID:
                songReportCleanupBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case BadgeAwardBatch.BATCH_ID:
                badgeAwardBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case RankingSnapshotBatch.BATCH_ID:
                rankingSnapshotBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case WeeklyPerfectRefreshBatch.BATCH_ID:
                weeklyPerfectRefreshBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case LpDecayBatch.BATCH_ID:
                lpDecayBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case SongAnswerGenerationBatch.BATCH_ID:
                songAnswerGenerationBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            case LoginStreakBatch.BATCH_ID:
                loginStreakBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);
                break;
            default:
                throw new IllegalArgumentException("실행할 수 없는 배치입니다: " + batchId);
        }
    }

    public int getScheduledCount() {
        return scheduledTasks.size();
    }

    public boolean isScheduled(String batchId) {
        return scheduledTasks.containsKey(batchId);
    }
}