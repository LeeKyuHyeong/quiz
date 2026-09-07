package com.kh.game.service;

import com.kh.game.entity.BatchAffectedSong;
import com.kh.game.entity.BatchAffectedSong.ActionType;
import com.kh.game.entity.BatchAffectedSong.AffectedReason;
import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.repository.BatchAffectedSongRepository;
import com.kh.game.repository.BatchConfigRepository;
import com.kh.game.repository.BatchExecutionHistoryRepository;
import com.kh.game.repository.SongRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BatchService {

    private final BatchConfigRepository batchConfigRepository;
    private final BatchExecutionHistoryRepository historyRepository;
    private final BatchAffectedSongRepository affectedSongRepository;
    private final SongRepository songRepository;

    /**
     * 초기 배치 설정 데이터 생성 및 업데이트
     */
    @PostConstruct
    @Transactional
    public void initBatchConfigs() {
        // 기존 데이터가 있으면 implemented 플래그 및 설정 업데이트
        if (batchConfigRepository.count() > 0) {
            updateImplementedFlags();
            updateBatchConfigs();
            return;
        }

        log.info("배치 설정 초기 데이터 생성");

        // 1. 방 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_ROOM_CLEANUP",
                "방 정리",
                "종료된 방 및 24시간 이상 대기 상태인 방을 자동 삭제합니다.",
                "0 0 * * * *",
                "매시간",
                "GameRoom",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 2. 채팅 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_CHAT_CLEANUP",
                "채팅 로그 정리",
                "30일이 지난 채팅 로그를 자동 삭제하여 DB 용량을 관리합니다.",
                "0 0 3 * * *",
                "매일 03:00",
                "GameRoomChat",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 3. 통계 집계
        batchConfigRepository.save(new BatchConfig(
                "BATCH_DAILY_STATS",
                "일일 통계 집계",
                "전일 게임 수, 참여자 수, 정답률 등 통계를 집계합니다.",
                "0 0 4 * * *",
                "매일 04:00",
                "DailyStats",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 4. 로그인 이력 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_LOGIN_HISTORY_CLEANUP",
                "로그인 이력 정리",
                "90일이 지난 로그인 이력을 삭제합니다.",
                "0 0 5 * * SUN",
                "매주 일요일 05:00",
                "MemberLoginHistory",
                BatchConfig.Priority.LOW,
                true  // 구현됨
        ));

        // 6. 비활성 회원 처리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_INACTIVE_MEMBER",
                "비활성 회원 처리",
                "6개월 이상 미접속 회원을 휴면 상태로 전환합니다.",
                "0 0 6 1 * *",
                "매월 1일 06:00",
                "Member",
                BatchConfig.Priority.LOW,
                true  // 구현됨
        ));

        // 7. 노래 파일 정합성 검사 (MP3 유효성 검증 포함)
        batchConfigRepository.save(new BatchConfig(
                "BATCH_SONG_FILE_CHECK",
                "MP3 파일 유효성 검사",
                "MP3 파일 존재 및 형식 유효성을 검사하고, 문제 있는 노래를 비활성화합니다.",
                "0 0 * * * *",
                "매시간",
                "Song",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 8. 게임 세션 정리 (구현됨)
        batchConfigRepository.save(new BatchConfig(
                "BATCH_SESSION_CLEANUP",
                "게임 세션 정리",
                "비정상 종료된 게임 세션과 오래된 PLAYING 상태 방을 정리합니다.",
                "0 30 * * * *",
                "매시간 30분",
                "GameSession",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 9. 인기 노래 분석
        batchConfigRepository.save(new BatchConfig(
                "BATCH_SONG_ANALYTICS",
                "인기 노래 분석",
                "주간 노래별 출제 횟수, 정답률, 스킵률을 분석합니다.",
                "0 0 7 * * MON",
                "매주 월요일 07:00",
                "SongAnalytics",
                BatchConfig.Priority.LOW,
                true  // 구현됨
        ));

        // 10. 시스템 상태 리포트
        batchConfigRepository.save(new BatchConfig(
                "BATCH_SYSTEM_REPORT",
                "시스템 상태 리포트",
                "DB 용량, 활성 사용자 수, 서버 상태 등 일일 리포트를 생성합니다.",
                "0 0 8 * * *",
                "매일 08:00",
                "SystemReport",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 11. 주간 랭킹 리셋
        batchConfigRepository.save(new BatchConfig(
                "BATCH_WEEKLY_RANKING_RESET",
                "주간 랭킹 리셋",
                "매주 월요일 주간 통계를 초기화하여 새로운 시즌을 시작합니다.",
                "0 0 6 * * MON",
                "매주 월요일 06:00",
                "Member",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 12. 게시판 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_BOARD_CLEANUP",
                "게시판 정리",
                "삭제 표시된 지 30일이 지난 게시글과 댓글을 영구 삭제합니다.",
                "0 0 3 * * *",
                "매일 03:00",
                "Board",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 13. 배치 실행 이력 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_EXECUTION_HISTORY_CLEANUP",
                "배치 실행 이력 정리",
                "30일이 지난 배치 실행 이력을 삭제하여 DB 용량을 관리합니다.",
                "0 0 4 * * SUN",
                "매주 일요일 04:00",
                "BatchExecutionHistory",
                BatchConfig.Priority.LOW,
                true  // 구현됨
        ));

        // 14. 게임 라운드 시도 기록 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_GAME_ROUND_ATTEMPT_CLEANUP",
                "게임 시도 기록 정리",
                "30일이 지난 게임 라운드 시도 기록을 삭제하여 DB 용량을 관리합니다.",
                "0 0 4 * * SUN",
                "매주 일요일 04:00",
                "GameRoundAttempt",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 15. 중복 곡 검사
        batchConfigRepository.save(new BatchConfig(
                "BATCH_DUPLICATE_SONG_CHECK",
                "중복 곡 검사",
                "같은 YouTube ID로 등록된 중복 곡을 찾아 나중에 생성된 곡을 비활성화합니다.",
                "0 0 5 * * *",
                "매일 05:00",
                "Song",
                BatchConfig.Priority.MEDIUM,
                true  // 구현됨
        ));

        // 16. YouTube 영상 유효성 검사
        batchConfigRepository.save(new BatchConfig(
                "BATCH_YOUTUBE_VIDEO_CHECK",
                "YouTube 영상 유효성 검사",
                "YouTube 영상의 삭제 여부와 임베드 가능 여부를 검사하고, 문제 있는 노래를 비활성화합니다.",
                "0 0 2 * * *",
                "매일 02:00",
                "Song",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 17. Solo 게임 세션 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_GAME_SESSION_CLEANUP",
                "Solo 게임 세션 정리",
                "24시간 이상 PLAYING 상태인 좀비 세션을 ABANDONED로 변경하고, 7일 이상 된 완료/포기 세션을 삭제합니다.",
                "0 0 2 * * *",
                "매일 02:00",
                "GameSession",
                BatchConfig.Priority.HIGH,
                true  // 구현됨
        ));

        // 18. 노래 신고 정리
        batchConfigRepository.save(new BatchConfig(
                "BATCH_SONG_REPORT_CLEANUP",
                "노래 신고 정리",
                "90일 이상 된 해결(RESOLVED)/반려(REJECTED) 신고를 삭제합니다. 처리 중인 신고(PENDING, CONFIRMED)는 유지됩니다.",
                "0 0 4 * * SUN",
                "매주 일요일 04:00",
                "SongReport",
                BatchConfig.Priority.LOW,
                true  // 구현됨
        ));

        log.info("배치 설정 초기 데이터 생성 완료: 17개");
    }

    /**
     * 모든 배치의 implemented 플래그를 true로 업데이트
     * (모든 배치가 구현되었으므로)
     */
    @Transactional
    public void updateImplementedFlags() {
        // 구현된 배치 ID 목록
        java.util.Set<String> implementedBatchIds = java.util.Set.of(
                "BATCH_SESSION_CLEANUP",
                "BATCH_ROOM_CLEANUP",
                "BATCH_CHAT_CLEANUP",
                "BATCH_DAILY_STATS",
                "BATCH_LOGIN_HISTORY_CLEANUP",
                "BATCH_INACTIVE_MEMBER",
                "BATCH_SONG_FILE_CHECK",
                "BATCH_SONG_ANALYTICS",
                "BATCH_SYSTEM_REPORT",
                "BATCH_WEEKLY_RANKING_RESET",
                "BATCH_MONTHLY_RANKING_RESET",
                "BATCH_RANKING_SNAPSHOT",
                "BATCH_BOARD_CLEANUP",
                "BATCH_EXECUTION_HISTORY_CLEANUP",
                "BATCH_GAME_ROUND_ATTEMPT_CLEANUP",
                "BATCH_DUPLICATE_SONG_CHECK",
                "BATCH_YOUTUBE_VIDEO_CHECK",
                "BATCH_GAME_SESSION_CLEANUP",
                "BATCH_SONG_REPORT_CLEANUP",
                "BATCH_BADGE_AWARD",
                "BATCH_FAN_CHALLENGE_PERFECT_CHECK",
                "BATCH_WEEKLY_PERFECT_REFRESH",
                "BATCH_LP_DECAY",
                "BATCH_SONG_ANSWER_GENERATION",
                "BATCH_LOGIN_STREAK"
        );

        int updatedCount = 0;
        for (BatchConfig config : batchConfigRepository.findAll()) {
            if (implementedBatchIds.contains(config.getBatchId()) && !config.getImplemented()) {
                config.setImplemented(true);
                updatedCount++;
                log.info("배치 구현 상태 업데이트: {} -> implemented=true", config.getBatchId());
            }
        }

        if (updatedCount > 0) {
            log.info("배치 구현 상태 업데이트 완료: {}개", updatedCount);
        }
    }

    /**
     * 배치 설정 업데이트 (기존 데이터 마이그레이션)
     */
    @Transactional
    public void updateBatchConfigs() {
        // BATCH_WEEKLY_RANKING_RESET: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_WEEKLY_RANKING_RESET")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_WEEKLY_RANKING_RESET",
                    "주간 랭킹 리셋",
                    "매주 월요일 주간 통계를 초기화하여 새로운 시즌을 시작합니다.",
                    "0 0 6 * * MON",
                    "매주 월요일 06:00",
                    "Member",
                    BatchConfig.Priority.HIGH,
                    true  // 구현됨
            ));
            log.info("BATCH_WEEKLY_RANKING_RESET 배치 설정 추가 완료");
        }

        // BATCH_SONG_FILE_CHECK: 매일 02:00 -> 매시간으로 변경
        batchConfigRepository.findById("BATCH_SONG_FILE_CHECK").ifPresent(config -> {
            boolean updated = false;

            // 이름 업데이트
            if (!"MP3 파일 유효성 검사".equals(config.getName())) {
                config.setName("MP3 파일 유효성 검사");
                updated = true;
            }

            // 설명 업데이트
            String newDesc = "MP3 파일 존재 및 형식 유효성을 검사하고, 문제 있는 노래를 비활성화합니다.";
            if (!newDesc.equals(config.getDescription())) {
                config.setDescription(newDesc);
                updated = true;
            }

            // cron 표현식 업데이트 (매시간)
            if (!"0 0 * * * *".equals(config.getCronExpression())) {
                config.setCronExpression("0 0 * * * *");
                config.setScheduleText("매시간");
                updated = true;
            }

            if (updated) {
                log.info("BATCH_SONG_FILE_CHECK 설정 업데이트 완료 (매시간 실행)");
            }
        });

        // BATCH_BOARD_CLEANUP: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_BOARD_CLEANUP")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_BOARD_CLEANUP",
                    "게시판 정리",
                    "삭제 표시된 지 30일이 지난 게시글과 댓글을 영구 삭제합니다.",
                    "0 0 3 * * *",
                    "매일 03:00",
                    "Board",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_BOARD_CLEANUP 배치 설정 추가 완료");
        }

        // BATCH_EXECUTION_HISTORY_CLEANUP: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_EXECUTION_HISTORY_CLEANUP")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_EXECUTION_HISTORY_CLEANUP",
                    "배치 실행 이력 정리",
                    "30일이 지난 배치 실행 이력을 삭제하여 DB 용량을 관리합니다.",
                    "0 0 4 * * SUN",
                    "매주 일요일 04:00",
                    "BatchExecutionHistory",
                    BatchConfig.Priority.LOW,
                    true  // 구현됨
            ));
            log.info("BATCH_EXECUTION_HISTORY_CLEANUP 배치 설정 추가 완료");
        }

        // BATCH_GAME_ROUND_ATTEMPT_CLEANUP: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_GAME_ROUND_ATTEMPT_CLEANUP")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_GAME_ROUND_ATTEMPT_CLEANUP",
                    "게임 시도 기록 정리",
                    "30일이 지난 게임 라운드 시도 기록을 삭제하여 DB 용량을 관리합니다.",
                    "0 0 4 * * SUN",
                    "매주 일요일 04:00",
                    "GameRoundAttempt",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_GAME_ROUND_ATTEMPT_CLEANUP 배치 설정 추가 완료");
        }

        // BATCH_DUPLICATE_SONG_CHECK: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_DUPLICATE_SONG_CHECK")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_DUPLICATE_SONG_CHECK",
                    "중복 곡 검사",
                    "같은 YouTube ID로 등록된 중복 곡을 찾아 나중에 생성된 곡을 비활성화합니다.",
                    "0 0 5 * * *",
                    "매일 05:00",
                    "Song",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_DUPLICATE_SONG_CHECK 배치 설정 추가 완료");
        }

        // BATCH_YOUTUBE_VIDEO_CHECK: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_YOUTUBE_VIDEO_CHECK")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_YOUTUBE_VIDEO_CHECK",
                    "YouTube 영상 유효성 검사",
                    "YouTube 영상의 삭제 여부와 임베드 가능 여부를 검사하고, 문제 있는 노래를 비활성화합니다.",
                    "0 0 2 * * *",
                    "매일 02:00",
                    "Song",
                    BatchConfig.Priority.HIGH,
                    true  // 구현됨
            ));
            log.info("BATCH_YOUTUBE_VIDEO_CHECK 배치 설정 추가 완료");
        }

        // BATCH_GAME_SESSION_CLEANUP: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_GAME_SESSION_CLEANUP")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_GAME_SESSION_CLEANUP",
                    "Solo 게임 세션 정리",
                    "24시간 이상 PLAYING 상태인 좀비 세션을 ABANDONED로 변경하고, 7일 이상 된 완료/포기 세션을 삭제합니다.",
                    "0 0 2 * * *",
                    "매일 02:00",
                    "GameSession",
                    BatchConfig.Priority.HIGH,
                    true  // 구현됨
            ));
            log.info("BATCH_GAME_SESSION_CLEANUP 배치 설정 추가 완료");
        }

        // BATCH_SONG_REPORT_CLEANUP: 새 배치 추가 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_SONG_REPORT_CLEANUP")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_SONG_REPORT_CLEANUP",
                    "노래 신고 정리",
                    "90일 이상 된 해결(RESOLVED)/반려(REJECTED) 신고를 삭제합니다. 처리 중인 신고(PENDING, CONFIRMED)는 유지됩니다.",
                    "0 0 4 * * SUN",
                    "매주 일요일 04:00",
                    "SongReport",
                    BatchConfig.Priority.LOW,
                    true  // 구현됨
            ));
            log.info("BATCH_SONG_REPORT_CLEANUP 배치 설정 추가 완료");
        }

        // BATCH_BADGE_AWARD: 뱃지 일괄 지급 배치 (기존 DB에 없으면 생성)
        if (!batchConfigRepository.existsById("BATCH_BADGE_AWARD")) {
            BatchConfig badgeAwardConfig = new BatchConfig(
                    "BATCH_BADGE_AWARD",
                    "뱃지 일괄 지급",
                    "기존 회원들의 기록을 기반으로 뱃지를 일괄 지급합니다. 수동 실행 전용입니다.",
                    "0 0 0 1 1 *",
                    "수동 실행 전용",
                    "MemberBadge",
                    BatchConfig.Priority.LOW,
                    true  // 구현됨
            );
            badgeAwardConfig.setEnabled(false);  // 기본 비활성화 (수동 실행 전용)
            batchConfigRepository.save(badgeAwardConfig);
            log.info("BATCH_BADGE_AWARD 배치 설정 추가 완료");
        }

        // BATCH_FAN_CHALLENGE_PERFECT_CHECK: 팬챌린지 퍼펙트 검사 배치 (폐지 — 고정단계 모델 전환으로 무동작, WeeklyPerfectRefresh로 대체)
        if (!batchConfigRepository.existsById("BATCH_FAN_CHALLENGE_PERFECT_CHECK")) {
            BatchConfig fanPerfectCheckConfig = new BatchConfig(
                    "BATCH_FAN_CHALLENGE_PERFECT_CHECK",
                    "팬챌린지 퍼펙트 검사(폐지)",
                    "[폐지] 고정단계 모델 전환으로 stageLevel 가드가 전 레코드를 스킵해 무동작. WeeklyPerfectRefresh가 대체.",
                    "0 0 4 * * *",
                    "매일 새벽 4시",
                    "FanChallengeRecord",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            );
            fanPerfectCheckConfig.setEnabled(false);  // 폐지 — 신규 설치 시 스케줄 등록 제외(기존 운영 DB는 관리자 토글로 비활성화)
            batchConfigRepository.save(fanPerfectCheckConfig);
            log.info("BATCH_FAN_CHALLENGE_PERFECT_CHECK 배치 설정 추가 완료(폐지, enabled=false)");
        }

        // BATCH_WEEKLY_PERFECT_REFRESH: 주간 퍼펙트 갱신 배치
        if (!batchConfigRepository.existsById("BATCH_WEEKLY_PERFECT_REFRESH")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_WEEKLY_PERFECT_REFRESH",
                    "주간 퍼펙트 갱신",
                    "곡 수 변경에 따른 현재시점 퍼펙트 상태를 갱신합니다. isPerfectClear는 달성시점, isCurrentPerfect는 현재시점 기준입니다.",
                    "0 0 4 * * MON",
                    "매주 월요일 04:00",
                    "FanChallengeRecord",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_WEEKLY_PERFECT_REFRESH 배치 설정 추가 완료");
        }

        // BATCH_MONTHLY_RANKING_RESET: 월간 랭킹 리셋 배치
        if (!batchConfigRepository.existsById("BATCH_MONTHLY_RANKING_RESET")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_MONTHLY_RANKING_RESET",
                    "월간 랭킹 리셋",
                    "매월 1일 00:00에 월간 30곡 최고점 통계를 초기화합니다.",
                    "0 0 0 1 * *",
                    "매월 1일 00:00",
                    "Member",
                    BatchConfig.Priority.HIGH,
                    true  // 구현됨
            ));
            log.info("BATCH_MONTHLY_RANKING_RESET 배치 설정 추가 완료");
        }

        // BATCH_RANKING_SNAPSHOT: 랭킹 스냅샷 배치 (RankingUpdateBatch 대체)
        if (!batchConfigRepository.existsById("BATCH_RANKING_SNAPSHOT")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_RANKING_SNAPSHOT",
                    "랭킹 스냅샷 저장",
                    "주간/월간 랭킹 리셋 전 Top 100 기록을 RankingHistory 테이블에 보관합니다.",
                    "0 50 5 * * MON",
                    "매주 월요일 05:50",
                    "RankingHistory",
                    BatchConfig.Priority.HIGH,
                    true  // 구현됨
            ));
            log.info("BATCH_RANKING_SNAPSHOT 배치 설정 추가 완료");
        }

        // 기존 BATCH_RANKING_UPDATE 설정이 있으면 삭제 (deprecated)
        if (batchConfigRepository.existsById("BATCH_RANKING_UPDATE")) {
            batchConfigRepository.deleteById("BATCH_RANKING_UPDATE");
            log.info("BATCH_RANKING_UPDATE 배치 설정 삭제 완료 (BATCH_RANKING_SNAPSHOT으로 대체됨)");
        }

        // BATCH_LP_DECAY: LP Decay 배치 (장기 미접속 유저 LP 감소)
        if (!batchConfigRepository.existsById("BATCH_LP_DECAY")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_LP_DECAY",
                    "LP Decay",
                    "30일 이상 멀티게임을 하지 않은 회원의 LP를 감소시킵니다. (7 LP/주)",
                    "0 0 5 * * MON",
                    "매주 월요일 05:00",
                    "Member",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_LP_DECAY 배치 설정 추가 완료");
        }

        // BATCH_SONG_ANSWER_GENERATION: 곡 정답 자동 생성 배치
        if (!batchConfigRepository.existsById("BATCH_SONG_ANSWER_GENERATION")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_SONG_ANSWER_GENERATION",
                    "곡 정답 자동 생성",
                    "SongAnswer가 없는 곡에 정답 변형(원본, 괄호제거, 한글발음 등)을 자동 생성합니다.",
                    "0 0 6 * * *",
                    "매일 06:00",
                    "SongAnswer",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_SONG_ANSWER_GENERATION 배치 설정 추가 완료");
        }

        // BATCH_LOGIN_STREAK: 연속 로그인 스트릭 리셋 배치
        if (!batchConfigRepository.existsById("BATCH_LOGIN_STREAK")) {
            batchConfigRepository.save(new BatchConfig(
                    "BATCH_LOGIN_STREAK",
                    "연속 로그인 스트릭 리셋",
                    "어제 로그인하지 않은 회원의 연속 로그인 스트릭을 리셋합니다.",
                    "0 30 0 * * *",
                    "매일 00:30",
                    "Member",
                    BatchConfig.Priority.MEDIUM,
                    true  // 구현됨
            ));
            log.info("BATCH_LOGIN_STREAK 배치 설정 추가 완료");
        }
    }

    /**
     * 전체 배치 목록 조회
     */
    public List<BatchConfig> findAll() {
        return batchConfigRepository.findAllByOrderByPriorityAscNameAsc();
    }

    /**
     * 배치 조회
     */
    public Optional<BatchConfig> findById(String batchId) {
        return batchConfigRepository.findById(batchId);
    }

    /**
     * 활성화된 & 구현된 배치 목록
     */
    public List<BatchConfig> findEnabledAndImplemented() {
        return batchConfigRepository.findByImplementedTrueAndEnabledTrue();
    }

    /**
     * 배치 설정 업데이트
     */
    @Transactional
    public void updateConfig(String batchId, String cronExpression, String scheduleText, Boolean enabled) {
        BatchConfig config = batchConfigRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("배치를 찾을 수 없습니다: " + batchId));

        if (cronExpression != null && !cronExpression.isEmpty()) {
            config.setCronExpression(cronExpression);
        }
        if (scheduleText != null) {
            config.setScheduleText(scheduleText);
        }
        if (enabled != null) {
            config.setEnabled(enabled);
        }

        log.info("배치 설정 업데이트: {} - cron={}, enabled={}", batchId, cronExpression, enabled);
    }

    /**
     * 배치 활성화/비활성화 토글
     */
    @Transactional
    public boolean toggleEnabled(String batchId) {
        BatchConfig config = batchConfigRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("배치를 찾을 수 없습니다: " + batchId));

        config.setEnabled(!config.getEnabled());
        log.info("배치 활성화 토글: {} -> {}", batchId, config.getEnabled());
        return config.getEnabled();
    }

    /**
     * 실행 결과 기록
     */
    @Transactional
    public void recordExecution(String batchId, BatchExecutionHistory.ExecutionType executionType,
                                BatchConfig.ExecutionResult result, String message,
                                int affectedCount, long executionTimeMs) {
        // BatchConfig 업데이트
        batchConfigRepository.findById(batchId).ifPresent(config -> {
            config.recordExecution(result, message, affectedCount, executionTimeMs);
        });

        // 실행 이력 저장
        BatchConfig config = batchConfigRepository.findById(batchId).orElse(null);
        String batchName = config != null ? config.getName() : batchId;

        BatchExecutionHistory history = new BatchExecutionHistory(batchId, batchName, executionType);
        history.complete(result, message, affectedCount, executionTimeMs);
        historyRepository.save(history);

        log.info("배치 실행 기록: {} - result={}, affected={}, time={}ms",
                batchId, result, affectedCount, executionTimeMs);
    }

    /**
     * 실행 이력 조회
     */
    public List<BatchExecutionHistory> getRecentHistory(String batchId) {
        return historyRepository.findTop10ByBatchIdOrderByExecutedAtDesc(batchId);
    }

    /**
     * 통계
     */
    public long countImplemented() {
        return batchConfigRepository.findAll().stream()
                .filter(BatchConfig::getImplemented)
                .count();
    }

    public long countEnabled() {
        return batchConfigRepository.findByEnabledTrue().size();
    }

    // ========== 배치 영향 곡 관리 메서드 ==========

    /**
     * 배치 실행 이력 생성 (배치 시작 시 호출)
     * - 새로운 트랜잭션으로 즉시 커밋하여 ID 할당
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchExecutionHistory createExecutionHistory(String batchId, BatchExecutionHistory.ExecutionType executionType) {
        BatchConfig config = batchConfigRepository.findById(batchId).orElse(null);
        String batchName = config != null ? config.getName() : batchId;

        BatchExecutionHistory history = new BatchExecutionHistory(batchId, batchName, executionType);
        // 임시로 결과 설정 (나중에 완료 시 업데이트)
        history.complete(BatchConfig.ExecutionResult.SUCCESS, "실행 중...", 0, 0L);
        return historyRepository.save(history);
    }

    /**
     * 영향받은 곡 기록
     */
    @Transactional
    public BatchAffectedSong recordAffectedSong(BatchExecutionHistory history, Song song,
                                                 ActionType actionType, AffectedReason reason, String reasonDetail) {
        BatchAffectedSong affected = new BatchAffectedSong(history, song, actionType, reason, reasonDetail);
        return affectedSongRepository.save(affected);
    }

    /**
     * 배치 실행 완료 처리
     */
    @Transactional
    public void completeExecution(BatchExecutionHistory history, BatchConfig.ExecutionResult result,
                                   String message, int affectedCount, long executionTimeMs) {
        history.complete(result, message, affectedCount, executionTimeMs);
        historyRepository.save(history);

        // BatchConfig도 업데이트
        batchConfigRepository.findById(history.getBatchId()).ifPresent(config -> {
            config.recordExecution(result, message, affectedCount, executionTimeMs);
        });

        log.info("배치 실행 완료 기록: {} - result={}, affected={}, time={}ms",
                history.getBatchId(), result, affectedCount, executionTimeMs);
    }

    /**
     * 영향받은 곡 목록 조회 (필터 포함)
     */
    public Page<BatchAffectedSong> getAffectedSongs(String batchId, Boolean isRestored, String keyword, Pageable pageable) {
        return affectedSongRepository.searchWithFilters(batchId, isRestored, keyword, pageable);
    }

    /**
     * 영향받은 곡 통계
     */
    public Map<String, Object> getAffectedSongStats() {
        Map<String, Object> stats = new HashMap<>();

        long total = affectedSongRepository.count();
        long unrestored = affectedSongRepository.countByIsRestoredFalse();
        long restored = affectedSongRepository.countByIsRestoredTrue();

        stats.put("total", total);
        stats.put("unrestored", unrestored);
        stats.put("restored", restored);

        // YouTube 배치 관련
        long youtubeTotal = affectedSongRepository.countByBatchId("BATCH_YOUTUBE_VIDEO_CHECK");
        long youtubeUnrestored = affectedSongRepository.countByBatchIdAndIsRestoredFalse("BATCH_YOUTUBE_VIDEO_CHECK");
        stats.put("youtubeTotal", youtubeTotal);
        stats.put("youtubeUnrestored", youtubeUnrestored);

        // 중복 검사 배치 관련
        long duplicateTotal = affectedSongRepository.countByBatchId("BATCH_DUPLICATE_SONG_CHECK");
        long duplicateUnrestored = affectedSongRepository.countByBatchIdAndIsRestoredFalse("BATCH_DUPLICATE_SONG_CHECK");
        stats.put("duplicateTotal", duplicateTotal);
        stats.put("duplicateUnrestored", duplicateUnrestored);

        return stats;
    }

    /**
     * 개별 곡 복구
     */
    @Transactional
    public boolean restoreSong(Long affectedId, Member admin) {
        BatchAffectedSong affected = affectedSongRepository.findById(affectedId).orElse(null);
        if (affected == null) {
            log.warn("영향받은 곡 기록을 찾을 수 없음: id={}", affectedId);
            return false;
        }

        if (affected.getIsRestored()) {
            log.warn("이미 복구된 곡: id={}", affectedId);
            return false;
        }

        // 곡 활성화
        Song song = affected.getSong();
        song.setUseYn("Y");
        songRepository.save(song);

        // 복구 기록
        affected.restore(admin);
        affectedSongRepository.save(affected);

        log.info("곡 복구 완료: affectedId={}, songId={}, admin={}",
                affectedId, song.getId(), admin.getNickname());
        return true;
    }

    /**
     * 일괄 복구 (배치 실행 이력 기준)
     */
    @Transactional
    public int restoreAllByHistory(Long historyId, Member admin) {
        List<BatchAffectedSong> affectedList = affectedSongRepository.findByHistoryIdOrderByIdDesc(historyId);
        int restoredCount = 0;

        for (BatchAffectedSong affected : affectedList) {
            if (!affected.getIsRestored()) {
                Song song = affected.getSong();
                song.setUseYn("Y");
                songRepository.save(song);

                affected.restore(admin);
                affectedSongRepository.save(affected);
                restoredCount++;
            }
        }

        log.info("일괄 복구 완료: historyId={}, restoredCount={}, admin={}",
                historyId, restoredCount, admin.getNickname());
        return restoredCount;
    }

    /**
     * 일괄 복구 (배치 ID 기준 - 미복구 전체)
     */
    @Transactional
    public int restoreAllByBatchId(String batchId, Member admin) {
        List<BatchAffectedSong> unrestoredList = affectedSongRepository.findUnrestoredByBatchId(batchId);
        int restoredCount = 0;

        for (BatchAffectedSong affected : unrestoredList) {
            Song song = affected.getSong();
            song.setUseYn("Y");
            songRepository.save(song);

            affected.restore(admin);
            affectedSongRepository.save(affected);
            restoredCount++;
        }

        log.info("배치 ID 기준 일괄 복구 완료: batchId={}, restoredCount={}, admin={}",
                batchId, restoredCount, admin.getNickname());
        return restoredCount;
    }

    /**
     * 영향받은 곡 단건 조회
     */
    public Optional<BatchAffectedSong> findAffectedSongById(Long id) {
        return affectedSongRepository.findById(id);
    }
}
