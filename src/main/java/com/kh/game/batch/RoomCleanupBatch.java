package com.kh.game.batch;

import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.GameRoom;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomCleanupBatch {

    private final GameRoomRepository gameRoomRepository;
    private final GameRoomChatRepository chatRepository;
    private final BatchService batchService;

    public static final String BATCH_ID = "BATCH_ROOM_CLEANUP";

    @Transactional
    public int execute(BatchExecutionHistory.ExecutionType executionType) {
        long startTime = System.currentTimeMillis();
        int totalAffected = 0;
        StringBuilder resultMessage = new StringBuilder();

        try {
            log.info("[{}] 배치 실행 시작", BATCH_ID);

            // 1. 24시간 이상 대기 상태인 방 종료
            LocalDateTime waitingThreshold = LocalDateTime.now().minusHours(24);
            List<GameRoom> staleWaitingRooms = gameRoomRepository.findStaleWaitingRooms(waitingThreshold);
            for (GameRoom room : staleWaitingRooms) {
                room.setStatus(GameRoom.RoomStatus.FINISHED);
                log.debug("오래된 대기 방 종료: {} ({})", room.getRoomCode(), room.getRoomName());
            }
            if (!staleWaitingRooms.isEmpty()) {
                resultMessage.append(String.format("24시간 대기 방 %d개 종료. ", staleWaitingRooms.size()));
            }
            totalAffected += staleWaitingRooms.size();

            // 1-2. 2시간 이상 변화 없는 게임중 방 종료 (방장이 사라져 라운드가 진행되지 않는 방)
            LocalDateTime playingThreshold = LocalDateTime.now().minusHours(2);
            List<GameRoom> stalePlayingRooms = gameRoomRepository.findByStatusAndUpdatedAtBefore(
                    GameRoom.RoomStatus.PLAYING, playingThreshold);
            for (GameRoom room : stalePlayingRooms) {
                room.setStatus(GameRoom.RoomStatus.FINISHED);
                log.debug("방치된 게임중 방 종료: {} ({})", room.getRoomCode(), room.getRoomName());
            }
            if (!stalePlayingRooms.isEmpty()) {
                resultMessage.append(String.format("2시간 방치 게임 방 %d개 종료. ", stalePlayingRooms.size()));
            }
            totalAffected += stalePlayingRooms.size();

            // 2. 종료된 방 중 3일 지난 방 삭제 (채팅 포함)
            LocalDateTime deleteThreshold = LocalDateTime.now().minusDays(3);
            List<GameRoom> oldFinishedRooms = gameRoomRepository.findByStatus(GameRoom.RoomStatus.FINISHED);
            int deletedCount = 0;
            for (GameRoom room : oldFinishedRooms) {
                if (room.getUpdatedAt() != null && room.getUpdatedAt().isBefore(deleteThreshold)) {
                    chatRepository.deleteByGameRoom(room);
                    gameRoomRepository.delete(room);
                    deletedCount++;
                    log.debug("오래된 방 삭제: {} ({})", room.getRoomCode(), room.getRoomName());
                }
            }
            if (deletedCount > 0) {
                resultMessage.append(String.format("3일 지난 방 %d개 삭제.", deletedCount));
            }
            totalAffected += deletedCount;

            if (totalAffected == 0) {
                resultMessage.append("정리할 방이 없습니다.");
            }

            long executionTime = System.currentTimeMillis() - startTime;

            batchService.recordExecution(
                    BATCH_ID,
                    executionType,
                    BatchConfig.ExecutionResult.SUCCESS,
                    resultMessage.toString().trim(),
                    totalAffected,
                    executionTime
            );

            log.info("[{}] 배치 실행 완료 - 영향: {}건, 소요시간: {}ms",
                    BATCH_ID, totalAffected, executionTime);

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
