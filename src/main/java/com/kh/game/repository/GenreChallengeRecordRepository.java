package com.kh.game.repository;

import com.kh.game.entity.Genre;
import com.kh.game.entity.GenreChallengeDifficulty;
import com.kh.game.entity.GenreChallengeRecord;
import com.kh.game.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
public interface GenreChallengeRecordRepository extends JpaRepository<GenreChallengeRecord, Long> {

    // 회원+장르+난이도별 기록 조회
    Optional<GenreChallengeRecord> findByMemberAndGenreAndDifficulty(
            Member member, Genre genre, GenreChallengeDifficulty difficulty);

    // 장르별 랭킹 (정답수 DESC > 시간 ASC) - 하드코어만 (공식 랭킹)
    @Query("SELECT r FROM GenreChallengeRecord r " +
           "WHERE r.genre = :genre AND r.difficulty = 'HARDCORE' " +
           "ORDER BY r.correctCount DESC, COALESCE(r.bestTimeMs, 999999999) ASC")
    List<GenreChallengeRecord> findTopByGenre(@Param("genre") Genre genre, Pageable pageable);

    // 장르 코드로 랭킹 조회 (정답수 DESC > 시간 ASC) - 하드코어만
    @Query("SELECT r FROM GenreChallengeRecord r " +
           "WHERE r.genre.code = :genreCode AND r.difficulty = 'HARDCORE' " +
           "ORDER BY r.correctCount DESC, COALESCE(r.bestTimeMs, 999999999) ASC")
    List<GenreChallengeRecord> findTopByGenreCode(@Param("genreCode") String genreCode, Pageable pageable);

    // 난이도별 장르 랭킹
    @Query("SELECT r FROM GenreChallengeRecord r " +
           "WHERE r.genre = :genre AND r.difficulty = :difficulty " +
           "ORDER BY r.correctCount DESC, COALESCE(r.bestTimeMs, 999999999) ASC")
    List<GenreChallengeRecord> findTopByGenreAndDifficulty(
            @Param("genre") Genre genre,
            @Param("difficulty") GenreChallengeDifficulty difficulty,
            Pageable pageable);

    // 회원의 모든 기록 조회
    List<GenreChallengeRecord> findByMemberOrderByAchievedAtDesc(Member member);

    // 장르별 도전자 수 (하드코어만)
    @Query("SELECT COUNT(r) FROM GenreChallengeRecord r " +
           "WHERE r.genre = :genre AND r.difficulty = 'HARDCORE'")
    long countByGenre(@Param("genre") Genre genre);

    // 장르 코드별 도전자 수 (하드코어만)
    @Query("SELECT COUNT(r) FROM GenreChallengeRecord r " +
           "WHERE r.genre.code = :genreCode AND r.difficulty = 'HARDCORE'")
    long countByGenreCode(@Param("genreCode") String genreCode);

    // 기록이 있는 장르 목록 (기록 수 기준 인기순, 하드코어만)
    @Query("SELECT r.genre, COUNT(r) as cnt FROM GenreChallengeRecord r " +
           "WHERE r.difficulty = 'HARDCORE' GROUP BY r.genre ORDER BY cnt DESC")
    List<Object[]> findPopularGenres(Pageable pageable);

    // ========== 관리자 페이지용 쿼리 ==========

    // 전체 기록 조회 (페이징, Member/Genre JOIN)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member JOIN FETCH r.genre")
    Page<GenreChallengeRecord> findAllWithMemberAndGenre(Pageable pageable);

    // 장르 필터 (페이징)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member JOIN FETCH r.genre " +
           "WHERE r.genre = :genre")
    Page<GenreChallengeRecord> findByGenre(@Param("genre") Genre genre, Pageable pageable);

    // 난이도 필터 (페이징)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member JOIN FETCH r.genre " +
           "WHERE r.difficulty = :difficulty")
    Page<GenreChallengeRecord> findByDifficulty(
            @Param("difficulty") GenreChallengeDifficulty difficulty, Pageable pageable);

    // 회원별 기록 조회 (페이징)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.genre " +
           "WHERE r.member = :member ORDER BY r.achievedAt DESC")
    Page<GenreChallengeRecord> findByMember(@Param("member") Member member, Pageable pageable);

    // 회원 ID로 기록 조회 (페이징)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.genre " +
           "WHERE r.member.id = :memberId ORDER BY r.achievedAt DESC")
    Page<GenreChallengeRecord> findByMemberId(@Param("memberId") Long memberId, Pageable pageable);

    // 장르별 통계 집계
    @Query("SELECT r.genre.code, r.genre.name, COUNT(r), MAX(r.correctCount) " +
           "FROM GenreChallengeRecord r GROUP BY r.genre.code, r.genre.name ORDER BY COUNT(r) DESC")
    List<Object[]> getGenreStatistics();

    // 오늘 도전 기록 수 (achievedAt 기준, 반열림 구간 [start, end))
    @Query("SELECT COUNT(r) FROM GenreChallengeRecord r WHERE r.achievedAt >= :start AND r.achievedAt < :end")
    long countByAchievedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // "오늘"은 Asia/Seoul 기준 (DB 세션 TZ에 의존하지 않도록 코드에 명시)
    default long countTodayRecords() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        return countByAchievedAtBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    // 고유 장르 수
    @Query("SELECT COUNT(DISTINCT r.genre) FROM GenreChallengeRecord r")
    long countDistinctGenres();

    // ========== 글로벌 랭킹용 쿼리 ==========

    // 총 정답수 랭킹 (전체 장르 합산, HARDCORE 기준)
    @Query("SELECT r.member.id, SUM(r.correctCount) as total FROM GenreChallengeRecord r " +
           "WHERE r.difficulty = 'HARDCORE' " +
           "GROUP BY r.member.id ORDER BY total DESC")
    List<Object[]> findTotalCorrectCountRanking(Pageable pageable);

    // 도전 장르 수 랭킹 (HARDCORE 기준)
    @Query("SELECT r.member.id, COUNT(DISTINCT r.genre) as cnt FROM GenreChallengeRecord r " +
           "WHERE r.difficulty = 'HARDCORE' " +
           "GROUP BY r.member.id ORDER BY cnt DESC")
    List<Object[]> findGenreClearCountRanking(Pageable pageable);

    // 최대 콤보 랭킹 (HARDCORE 기준)
    @Query("SELECT r.member.id, MAX(r.maxCombo) as maxCombo FROM GenreChallengeRecord r " +
           "WHERE r.difficulty = 'HARDCORE' " +
           "GROUP BY r.member.id ORDER BY maxCombo DESC")
    List<Object[]> findMaxComboRanking(Pageable pageable);

    // ========== 홈 페이지용 쿼리 ==========

    // HARDCORE 기록이 있는 모든 장르 코드 목록
    @Query("SELECT DISTINCT r.genre.code FROM GenreChallengeRecord r " +
           "WHERE r.difficulty = 'HARDCORE'")
    List<String> findAllGenreCodesWithHardcoreRecords();

    // 장르 코드로 1위 기록 조회 (정답수 DESC > 시간 ASC)
    @Query("SELECT r FROM GenreChallengeRecord r " +
           "WHERE r.genre.code = :genreCode AND r.difficulty = 'HARDCORE' " +
           "ORDER BY r.correctCount DESC, COALESCE(r.bestTimeMs, 999999999) ASC")
    List<GenreChallengeRecord> findTopByGenreCodeAndHardcore(
            @Param("genreCode") String genreCode, Pageable pageable);

    // ========== 관리자 복합 필터 쿼리 ==========

    // 장르 + 난이도 필터
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member m JOIN FETCH r.genre g " +
           "WHERE r.genre = :genre AND r.difficulty = :difficulty")
    Page<GenreChallengeRecord> findByGenreAndDifficulty(
            @Param("genre") Genre genre,
            @Param("difficulty") GenreChallengeDifficulty difficulty,
            Pageable pageable);

    // 키워드 검색 (회원 닉네임 포함)
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member m JOIN FETCH r.genre g " +
           "WHERE m.nickname LIKE %:keyword%")
    Page<GenreChallengeRecord> findByMemberNicknameContaining(
            @Param("keyword") String keyword, Pageable pageable);

    // 키워드 + 장르 필터
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member m JOIN FETCH r.genre g " +
           "WHERE m.nickname LIKE %:keyword% AND r.genre = :genre")
    Page<GenreChallengeRecord> findByMemberNicknameContainingAndGenre(
            @Param("keyword") String keyword,
            @Param("genre") Genre genre,
            Pageable pageable);

    // 키워드 + 난이도 필터
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member m JOIN FETCH r.genre g " +
           "WHERE m.nickname LIKE %:keyword% AND r.difficulty = :difficulty")
    Page<GenreChallengeRecord> findByMemberNicknameContainingAndDifficulty(
            @Param("keyword") String keyword,
            @Param("difficulty") GenreChallengeDifficulty difficulty,
            Pageable pageable);

    // 키워드 + 장르 + 난이도 필터
    @Query("SELECT r FROM GenreChallengeRecord r JOIN FETCH r.member m JOIN FETCH r.genre g " +
           "WHERE m.nickname LIKE %:keyword% AND r.genre = :genre AND r.difficulty = :difficulty")
    Page<GenreChallengeRecord> findByMemberNicknameContainingAndGenreAndDifficulty(
            @Param("keyword") String keyword,
            @Param("genre") Genre genre,
            @Param("difficulty") GenreChallengeDifficulty difficulty,
            Pageable pageable);
}
