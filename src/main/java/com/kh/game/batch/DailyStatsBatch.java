package com.kh.game.batch;

import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.DailyStats;
import com.kh.game.entity.GameRoom;
import com.kh.game.repository.*;
import com.kh.game.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyStatsBatch {

    private final DailyStatsRepository dailyStatsRepository;
    private final GameRoomRepository gameRoomRepository;
    private final GameRoomParticipantRepository participantRepository;
    private final GameRoomChatRepository chatRepository;
    private final MemberRepository memberRepository;
    private final MemberLoginHistoryRepository loginHistoryRepository;
    private final BatchService batchService;

    public static final String BATCH_ID = "BATCH_DAILY_STATS";

    @Transactional
    public int execute(BatchExecutionHistory.ExecutionType executionType) {
        long startTime = System.currentTimeMillis();
        int totalAffected = 0;
        StringBuilder resultMessage = new StringBuilder();

        try {
            log.info("[{}] 배치 실행 시작", BATCH_ID);

            // 전일 통계 집계
            LocalDate yesterday = LocalDate.now().minusDays(1);
            LocalDateTime startOfDay = yesterday.atStartOfDay();
            LocalDateTime endOfDay = yesterday.plusDays(1).atStartOfDay();

            // 이미 집계된 데이터가 있는지 확인
            DailyStats stats = dailyStatsRepository.findByStatDate(yesterday)
                    .orElse(new DailyStats(yesterday));

            // 게임 방 통계 (DB 레벨 카운트)
            long totalRooms = gameRoomRepository.countByCreatedAtBetween(startOfDay, endOfDay);
            stats.setTotalRooms((int) totalRooms);

            // 종료된 게임 수 (DB 레벨 카운트)
            long finishedGames = gameRoomRepository.countByStatusAndUpdatedAtBetween(
                    GameRoom.RoomStatus.FINISHED, startOfDay, endOfDay);
            stats.setTotalGames((int) finishedGames);

            // 참가자 수 - 어제 종료된 방들의 연인원 (DB 레벨 카운트)
            long participants = participantRepository.countByRoomStatusAndUpdatedAtBetween(
                    GameRoom.RoomStatus.FINISHED, startOfDay, endOfDay);
            stats.setTotalParticipants((int) participants);

            // 플레이된 라운드 수 - 어제 종료된 방들의 진행 라운드 합계 (DB 레벨 카운트)
            long roundsPlayed = gameRoomRepository.sumCurrentRoundByStatusAndUpdatedAtBetween(
                    GameRoom.RoomStatus.FINISHED, startOfDay, endOfDay);
            stats.setTotalRoundsPlayed((int) roundsPlayed);

            // 정답 수 - 어제 종료된 방 참가자들의 정답 합계 (DB 레벨 카운트)
            long correctAnswers = participantRepository.sumCorrectCountByRoomStatusAndUpdatedAtBetween(
                    GameRoom.RoomStatus.FINISHED, startOfDay, endOfDay);
            stats.setTotalCorrectAnswers((int) correctAnswers);

            // 채팅 수 (DB 레벨 카운트)
            long chatCount = chatRepository.countByCreatedAtBetween(startOfDay, endOfDay);
            stats.setTotalChats((int) chatCount);

            // 신규 가입자 수 (DB 레벨 카운트)
            long newMembers = memberRepository.countByCreatedAtBetween(startOfDay, endOfDay);
            stats.setNewMembers((int) newMembers);

            // 활성 사용자 - 전일 로그인 성공한 고유 회원 수 (DB 레벨 카운트)
            long activeMembers = loginHistoryRepository.countDistinctActiveMembersBetween(startOfDay, endOfDay);
            stats.setActiveMembers((int) activeMembers);

            dailyStatsRepository.save(stats);
            totalAffected = 1;

            // 정합성 점검 - 멀티플레이 불변식 위반 시 경고 수집
            // (ExecutionResult에 WARN 값이 없으므로 SUCCESS 유지 + 메시지 접두로 노출)
            List<String> warnings = new ArrayList<>();
            if (finishedGames > 0 && roundsPlayed == 0) warnings.add("게임>0인데 라운드=0");
            if (finishedGames > 0 && participants == 0) warnings.add("게임>0인데 참가자=0");
            if (finishedGames == 0 && (participants > 0 || roundsPlayed > 0 || correctAnswers > 0))
                warnings.add("게임=0인데 활동기록 존재");
            if (correctAnswers > roundsPlayed) warnings.add("정답>라운드(라운드당 최대 1정답 위반)");

            if (!warnings.isEmpty()) {
                resultMessage.append("⚠️ 정합성 경고: ")
                        .append(String.join("; ", warnings))
                        .append("; ");
            }

            resultMessage.append(String.format(
                    "%s 통계 집계 완료. 게임: %d, 방: %d, 참가자: %d, 라운드: %d, 정답: %d, 채팅: %d, 신규: %d, 활성: %d",
                    yesterday, stats.getTotalGames(), stats.getTotalRooms(),
                    stats.getTotalParticipants(), stats.getTotalRoundsPlayed(), stats.getTotalCorrectAnswers(),
                    stats.getTotalChats(), stats.getNewMembers(), stats.getActiveMembers()
            ));

            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                    BATCH_ID,
                    executionType,
                    BatchConfig.ExecutionResult.SUCCESS,
                    resultMessage.toString().trim(),
                    totalAffected,
                    executionTime
            );

            log.info("[{}] 배치 실행 완료 - 소요시간: {}ms", BATCH_ID, executionTime);

            return totalAffected;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                    BATCH_ID,
                    executionType,
                    BatchConfig.ExecutionResult.FAIL,
                    "오류 발생: " + e.getMessage(),
                    totalAffected,
                    executionTime
            );

            log.error("[{}] 배치 실행 실패", BATCH_ID, e);
            throw new RuntimeException("배치 실행 실패: " + e.getMessage(), e);
        }
    }
}
