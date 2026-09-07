package com.kh.game.repository;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameRoomParticipantRepository extends JpaRepository<GameRoomParticipant, Long> {

    // 방의 참가자 목록
    List<GameRoomParticipant> findByGameRoomOrderByJoinedAtAsc(GameRoom gameRoom);

    // 방의 활성 참가자 목록 (대기실용 - JOINED만)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.gameRoom = :room AND p.status = 'JOINED' ORDER BY p.joinedAt ASC")
    List<GameRoomParticipant> findActiveParticipants(@Param("room") GameRoom room);

    // 방의 게임중인 참가자 목록 (JOINED 또는 PLAYING)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.gameRoom = :room AND p.status IN ('JOINED', 'PLAYING') ORDER BY p.joinedAt ASC")
    List<GameRoomParticipant> findGameParticipants(@Param("room") GameRoom room);

    // 특정 회원의 특정 방 참가 정보
    Optional<GameRoomParticipant> findByGameRoomAndMember(GameRoom gameRoom, Member member);

    // 특정 회원이 참가중인 방 (활성 상태) - 가장 최근 1개만
    // 방 상태가 WAITING 또는 PLAYING인 경우만 확인 (FINISHED 방은 제외)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.member = :member AND p.status IN ('JOINED', 'PLAYING') AND p.gameRoom.status IN ('WAITING', 'PLAYING') ORDER BY p.joinedAt DESC LIMIT 1")
    Optional<GameRoomParticipant> findActiveParticipation(@Param("member") Member member);

    // 특정 회원의 모든 활성 참가 정보 (정리용)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.member = :member AND p.status IN ('JOINED', 'PLAYING')")
    List<GameRoomParticipant> findAllActiveParticipations(@Param("member") Member member);

    // 특정 회원의 종료된 방 참가 정보 (정리용)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.member = :member AND p.status IN ('JOINED', 'PLAYING') AND p.gameRoom.status = 'FINISHED'")
    List<GameRoomParticipant> findStaleParticipations(@Param("member") Member member);

    // 방의 준비된 참가자 수
    @Query("SELECT COUNT(p) FROM GameRoomParticipant p WHERE p.gameRoom = :room AND p.isReady = true AND p.status = 'JOINED'")
    int countReadyParticipants(@Param("room") GameRoom room);

    // 방의 참가자 수
    @Query("SELECT COUNT(p) FROM GameRoomParticipant p WHERE p.gameRoom = :room AND p.status IN ('JOINED', 'PLAYING')")
    int countActiveParticipants(@Param("room") GameRoom room);

    // 점수 순 정렬 (게임중 - JOINED 또는 PLAYING)
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.gameRoom = :room AND p.status IN ('JOINED', 'PLAYING') ORDER BY p.score DESC")
    List<GameRoomParticipant> findByGameRoomOrderByScoreDesc(@Param("room") GameRoom room);

    // 점수 순 정렬 (결과용 - 모든 참가자, LEFT 제외하지 않음)
    // 게임 종료 후 결과 화면에서 모든 참가자를 표시하기 위해 사용
    @Query("SELECT p FROM GameRoomParticipant p WHERE p.gameRoom = :room ORDER BY p.score DESC")
    List<GameRoomParticipant> findAllByGameRoomOrderByScoreDesc(@Param("room") GameRoom room);

    // 회원이 해당 방에 참가중인지
    boolean existsByGameRoomAndMemberAndStatus(GameRoom gameRoom, Member member, GameRoomParticipant.ParticipantStatus status);

    // ========== 관리자 회원관리용 - 실시간 게임 수 집계 ==========

    /**
     * 여러 회원의 완료된 멀티게임 참여 수를 한 번에 조회 (N+1 방지)
     * FINISHED 상태인 방의 참여만 카운트
     * @return List of [memberId, count]
     */
    @Query("SELECT p.member.id, COUNT(p) FROM GameRoomParticipant p " +
           "WHERE p.member.id IN :memberIds AND p.gameRoom.status = 'FINISHED' " +
           "GROUP BY p.member.id")
    List<Object[]> countFinishedGamesByMemberIds(@Param("memberIds") List<Long> memberIds);

    // ========== 일일 통계 배치용 (멀티플레이) ==========

    // 특정 상태의 방들에 대한 참가자 수(연인원) - 기간별
    @Query("SELECT COUNT(p) FROM GameRoomParticipant p " +
           "WHERE p.gameRoom.status = :status AND p.gameRoom.updatedAt BETWEEN :start AND :end")
    long countByRoomStatusAndUpdatedAtBetween(@Param("status") GameRoom.RoomStatus status,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    // 특정 상태의 방들에 대한 정답 수 합계 - 기간별
    @Query("SELECT COALESCE(SUM(p.correctCount), 0) FROM GameRoomParticipant p " +
           "WHERE p.gameRoom.status = :status AND p.gameRoom.updatedAt BETWEEN :start AND :end")
    long sumCorrectCountByRoomStatusAndUpdatedAtBetween(@Param("status") GameRoom.RoomStatus status,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);
}