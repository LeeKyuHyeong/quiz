package com.kh.game.repository;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameRoomRepository extends JpaRepository<GameRoom, Long> {

    // 방 코드로 조회
    Optional<GameRoom> findByRoomCode(String roomCode);

    // 방 코드 존재 여부
    boolean existsByRoomCode(String roomCode);

    // 대기중인 공개 방 목록 (참가 가능한 방)
    @Query("SELECT r FROM GameRoom r WHERE r.status = 'WAITING' AND r.isPrivate = false " +
            "AND SIZE(r.participants) < r.maxPlayers ORDER BY r.createdAt DESC")
    List<GameRoom> findAvailableRooms();

    // 대기중인 공개 방 목록 (페이징)
    @Query("SELECT r FROM GameRoom r WHERE r.status = 'WAITING' AND r.isPrivate = false " +
            "AND SIZE(r.participants) < r.maxPlayers ORDER BY r.createdAt DESC")
    Page<GameRoom> findAvailableRooms(Pageable pageable);

    // 특정 회원이 방장인 방 조회
    List<GameRoom> findByHostAndStatus(Member host, GameRoom.RoomStatus status);

    // 특정 회원이 참가중인 방 조회
    @Query("SELECT r FROM GameRoom r JOIN r.participants p " +
            "WHERE p.member = :member AND p.status = 'JOINED' AND r.status IN ('WAITING', 'PLAYING')")
    Optional<GameRoom> findActiveRoomByMember(@Param("member") Member member);

    // 방 이름으로 검색
    @Query("SELECT r FROM GameRoom r WHERE r.status = 'WAITING' AND r.isPrivate = false " +
            "AND r.roomName LIKE %:keyword% ORDER BY r.createdAt DESC")
    List<GameRoom> searchByRoomName(@Param("keyword") String keyword);

    // 상태별 방 조회
    List<GameRoom> findByStatus(GameRoom.RoomStatus status);

    // 상태별 방 조회 (페이징)
    Page<GameRoom> findByStatusOrderByCreatedAtDesc(GameRoom.RoomStatus status, Pageable pageable);

    // 전체 방 목록 (관리자용, 페이징)
    Page<GameRoom> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 방 이름/코드 검색 (관리자용)
    @Query("SELECT r FROM GameRoom r WHERE r.roomName LIKE %:keyword% OR r.roomCode LIKE %:keyword% ORDER BY r.createdAt DESC")
    Page<GameRoom> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    // 방 이름/코드 + 상태 동시 검색 (관리자용)
    @Query("SELECT r FROM GameRoom r WHERE (r.roomName LIKE %:keyword% OR r.roomCode LIKE %:keyword%) AND r.status = :status ORDER BY r.createdAt DESC")
    Page<GameRoom> searchByKeywordAndStatus(@Param("keyword") String keyword, @Param("status") GameRoom.RoomStatus status, Pageable pageable);

    // 활성 방 (WAITING, PLAYING) 수
    @Query("SELECT COUNT(r) FROM GameRoom r WHERE r.status IN ('WAITING', 'PLAYING')")
    long countActiveRooms();

    // 상태별 카운트
    long countByStatus(GameRoom.RoomStatus status);

    // 기간별 생성된 방 수 (배치용)
    long countByCreatedAtBetween(java.time.LocalDateTime start, java.time.LocalDateTime end);

    // 기간별 종료된 게임 수 (배치용)
    long countByStatusAndUpdatedAtBetween(GameRoom.RoomStatus status, java.time.LocalDateTime start, java.time.LocalDateTime end);

    // 기간별 종료된 방들의 진행 라운드 수 합계 (일일 통계 배치용)
    @Query("SELECT COALESCE(SUM(r.currentRound), 0) FROM GameRoom r " +
            "WHERE r.status = :status AND r.updatedAt BETWEEN :start AND :end")
    long sumCurrentRoundByStatusAndUpdatedAtBetween(@Param("status") GameRoom.RoomStatus status,
                                                    @Param("start") java.time.LocalDateTime start,
                                                    @Param("end") java.time.LocalDateTime end);

    // 오래된 대기 방 조회 (정리용)
    @Query("SELECT r FROM GameRoom r WHERE r.status = 'WAITING' " +
            "AND r.updatedAt < :threshold")
    List<GameRoom> findStaleWaitingRooms(@Param("threshold") java.time.LocalDateTime threshold);

    // currentSong 참조를 null로 설정 (Song 삭제 전 호출)
    @Modifying
    @Query("UPDATE GameRoom r SET r.currentSong = null WHERE r.currentSong.id = :songId")
    void clearCurrentSongReference(@Param("songId") Long songId);
}