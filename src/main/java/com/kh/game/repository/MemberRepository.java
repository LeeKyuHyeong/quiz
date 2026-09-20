package com.kh.game.repository;

import com.kh.game.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 닉네임으로 시작하는 회원 목록 (동명이인 처리용)
    @Query("SELECT m.nickname FROM Member m WHERE m.nickname LIKE :nickname% ORDER BY m.nickname")
    List<String> findNicknamesStartingWith(String nickname);

    // 활성 회원만 조회
    Optional<Member> findByEmailAndStatus(String email, Member.MemberStatus status);

    // 랭킹 조회 (총점 기준)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.totalGames > 0 ORDER BY m.totalScore DESC")
    List<Member> findTopByTotalScore(Pageable pageable);

    // 랭킹 조회 (정답률 기준)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.totalRounds > 0 ORDER BY (m.totalCorrect * 1.0 / m.totalRounds) DESC")
    List<Member> findTopByAccuracy(Pageable pageable);

    // 랭킹 조회 (게임 수 기준)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' ORDER BY m.totalGames DESC")
    List<Member> findTopByTotalGames(Pageable pageable);

    // ========== Solo Guess (내가맞추기) 랭킹 조회 ==========

    // 1. 누적 총점 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames > 0 ORDER BY m.guessScore DESC")
    List<Member> findTopGuessRankingByScore(Pageable pageable);

    // 2. 평균 정답률 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessRounds > 0 ORDER BY (m.guessCorrect * 1.0 / m.guessRounds) DESC")
    List<Member> findTopGuessRankingByAccuracy(Pageable pageable);

    // 3. 평균 점수 기준 (게임당 평균)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames > 0 ORDER BY (m.guessScore * 1.0 / m.guessGames) DESC")
    List<Member> findTopGuessRankingByAvgScore(Pageable pageable);

    // 4. 최다 정답 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessCorrect > 0 ORDER BY m.guessCorrect DESC")
    List<Member> findTopGuessRankingByCorrect(Pageable pageable);

    // 5. 플레이왕 - 게임 수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames > 0 ORDER BY m.guessGames DESC")
    List<Member> findTopGuessRankingByGames(Pageable pageable);

    // 6. 도전왕 - 라운드 수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessRounds > 0 ORDER BY m.guessRounds DESC")
    List<Member> findTopGuessRankingByRounds(Pageable pageable);

    // 7. 라운드별 평균점수 기준 (10게임 이상)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames >= 10 AND m.guessRounds > 0 ORDER BY (m.guessScore * 1.0 / m.guessRounds) DESC")
    List<Member> findTopGuessRankingByAvgScorePerRound(Pageable pageable);

    // 8. 정답률 기준 (10게임 이상)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames >= 10 AND m.guessRounds > 0 ORDER BY (m.guessCorrect * 1.0 / m.guessRounds) DESC")
    List<Member> findTopGuessRankingByAccuracyMin10(Pageable pageable);

    // ========== Retro Game (레트로) 랭킹 조회 ==========

    // 1. 누적 총점 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.retroGames > 0 ORDER BY m.retroScore DESC")
    List<Member> findTopRetroRankingByScore(Pageable pageable);

    // 2. 평균 정답률 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.retroRounds > 0 ORDER BY (m.retroCorrect * 1.0 / m.retroRounds) DESC")
    List<Member> findTopRetroRankingByAccuracy(Pageable pageable);

    // 3. 게임 수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.retroGames > 0 ORDER BY m.retroGames DESC")
    List<Member> findTopRetroRankingByGames(Pageable pageable);

    // 4. 주간 레트로 총점
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyRetroGames > 0 ORDER BY m.weeklyRetroScore DESC")
    List<Member> findTopWeeklyRetroRankingByScore(Pageable pageable);

    // 5. 레트로 30곡 최고점 (역대)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.retroBest30Score IS NOT NULL " +
           "ORDER BY m.retroBest30Score DESC, m.retroBest30At ASC")
    List<Member> findRetroBest30Ranking(Pageable pageable);

    // 6. 레트로 30곡 주간 최고점
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyRetroBest30Score IS NOT NULL " +
           "ORDER BY m.weeklyRetroBest30Score DESC")
    List<Member> findWeeklyRetroBest30Ranking(Pageable pageable);

    // 내 레트로 총점 순위
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.retroGames > 0 AND m.retroScore > :score")
    long countMembersWithHigherRetroScore(int score);

    // 레트로 총 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.retroGames > 0")
    long countRetroParticipants();

    // 내 레트로 30곡 순위
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.retroBest30Score > :score")
    long countMembersWithHigherRetroBest30Score(int score);

    // 레트로 30곡 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.retroBest30Score IS NOT NULL")
    long countRetroBest30Participants();

    // ========== Multiplayer (멀티게임) 랭킹 조회 ==========

    // 총점 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 ORDER BY m.multiScore DESC")
    List<Member> findTopMultiRankingByScore(Pageable pageable);

    // 정답률 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiRounds > 0 ORDER BY (m.multiCorrect * 1.0 / m.multiRounds) DESC")
    List<Member> findTopMultiRankingByAccuracy(Pageable pageable);

    // 게임 수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 ORDER BY m.multiGames DESC")
    List<Member> findTopMultiRankingByGames(Pageable pageable);

    // 검색
    Page<Member> findByEmailContainingOrNicknameContaining(String email, String nickname, Pageable pageable);

    // 상태별 조회
    Page<Member> findByStatus(Member.MemberStatus status, Pageable pageable);

    // 상태별 조회 (List)
    List<Member> findByStatus(Member.MemberStatus status);

    // ========== 주간 랭킹 조회 ==========

    // 주간 내가맞추기 총점
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyGuessGames > 0 ORDER BY m.weeklyGuessScore DESC")
    List<Member> findTopWeeklyGuessRankingByScore(Pageable pageable);

    // 주간 멀티게임 총점
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyMultiGames > 0 ORDER BY m.weeklyMultiScore DESC")
    List<Member> findTopWeeklyMultiRankingByScore(Pageable pageable);

    // ========== 최고 기록 랭킹 조회 ==========

    // 내가맞추기 최고 점수
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.bestGuessScore > 0 ORDER BY m.bestGuessScore DESC")
    List<Member> findTopGuessBestScore(Pageable pageable);

    // 멀티게임 최고 점수
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.bestMultiScore > 0 ORDER BY m.bestMultiScore DESC")
    List<Member> findTopMultiBestScore(Pageable pageable);

    // ========== 내 순위 조회 ==========

    // 내가맞추기 총점 순위 (나보다 높은 점수 가진 사람 수)
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames > 0 AND m.guessScore > :score")
    long countMembersWithHigherGuessScore(int score);

    // 내가맞추기 총 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.guessGames > 0")
    long countGuessParticipants();

    // ========== 멀티게임 LP 티어 랭킹 조회 ==========

    // 멀티 티어 + LP 기준 (티어 내림차순, 같은 티어면 LP 내림차순)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 " +
           "ORDER BY CASE m.multiTier " +
           "WHEN com.kh.game.entity.MultiTier.CHALLENGER THEN 6 " +
           "WHEN com.kh.game.entity.MultiTier.MASTER THEN 5 " +
           "WHEN com.kh.game.entity.MultiTier.DIAMOND THEN 4 " +
           "WHEN com.kh.game.entity.MultiTier.PLATINUM THEN 3 " +
           "WHEN com.kh.game.entity.MultiTier.GOLD THEN 2 " +
           "WHEN com.kh.game.entity.MultiTier.SILVER THEN 1 " +
           "WHEN com.kh.game.entity.MultiTier.BRONZE THEN 0 " +
           "ELSE -1 END DESC, " +
           "COALESCE(m.multiLp, 0) DESC")
    List<Member> findTopMultiTierRanking(Pageable pageable);

    // 멀티게임 1등 횟수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiWins > 0 ORDER BY m.multiWins DESC")
    List<Member> findTopMultiWins(Pageable pageable);

    // 멀티게임 Top3 횟수 기준
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiTop3 > 0 ORDER BY m.multiTop3 DESC")
    List<Member> findTopMultiTop3(Pageable pageable);

    // 내 멀티 티어 순위 (나보다 높은 티어 + LP 가진 사람 수)
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 " +
           "AND (CASE m.multiTier " +
           "WHEN com.kh.game.entity.MultiTier.CHALLENGER THEN 6 " +
           "WHEN com.kh.game.entity.MultiTier.MASTER THEN 5 " +
           "WHEN com.kh.game.entity.MultiTier.DIAMOND THEN 4 " +
           "WHEN com.kh.game.entity.MultiTier.PLATINUM THEN 3 " +
           "WHEN com.kh.game.entity.MultiTier.GOLD THEN 2 " +
           "WHEN com.kh.game.entity.MultiTier.SILVER THEN 1 " +
           "WHEN com.kh.game.entity.MultiTier.BRONZE THEN 0 " +
           "ELSE -1 END > CASE :tier " +
           "WHEN com.kh.game.entity.MultiTier.CHALLENGER THEN 6 " +
           "WHEN com.kh.game.entity.MultiTier.MASTER THEN 5 " +
           "WHEN com.kh.game.entity.MultiTier.DIAMOND THEN 4 " +
           "WHEN com.kh.game.entity.MultiTier.PLATINUM THEN 3 " +
           "WHEN com.kh.game.entity.MultiTier.GOLD THEN 2 " +
           "WHEN com.kh.game.entity.MultiTier.SILVER THEN 1 " +
           "WHEN com.kh.game.entity.MultiTier.BRONZE THEN 0 " +
           "ELSE -1 END " +
           "OR (m.multiTier = :tier AND COALESCE(m.multiLp, 0) > :lp))")
    long countMembersWithHigherMultiTier(com.kh.game.entity.MultiTier tier, int lp);

    // 멀티 티어별 회원 수
    @Query("SELECT m.multiTier, COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 " +
           "GROUP BY m.multiTier " +
           "ORDER BY CASE m.multiTier " +
           "WHEN com.kh.game.entity.MultiTier.BRONZE THEN 0 " +
           "WHEN com.kh.game.entity.MultiTier.SILVER THEN 1 " +
           "WHEN com.kh.game.entity.MultiTier.GOLD THEN 2 " +
           "WHEN com.kh.game.entity.MultiTier.PLATINUM THEN 3 " +
           "WHEN com.kh.game.entity.MultiTier.DIAMOND THEN 4 " +
           "WHEN com.kh.game.entity.MultiTier.MASTER THEN 5 " +
           "WHEN com.kh.game.entity.MultiTier.CHALLENGER THEN 6 " +
           "ELSE -1 END")
    List<Object[]> countByMultiTier();

    // ========== 30곡 최고점 랭킹 조회 ==========

    // 주간 30곡 최고점 (점수 내림차순, 같은 점수면 먼저 달성한 사람 우선)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyBest30Score IS NOT NULL " +
           "ORDER BY m.weeklyBest30Score DESC, m.weeklyBest30At ASC")
    List<Member> findWeeklyBest30Ranking(Pageable pageable);

    // 월간 30곡 최고점
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.monthlyBest30Score IS NOT NULL " +
           "ORDER BY m.monthlyBest30Score DESC, m.monthlyBest30At ASC")
    List<Member> findMonthlyBest30Ranking(Pageable pageable);

    // 역대 30곡 최고점 (명예의 전당)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.allTimeBest30Score IS NOT NULL " +
           "ORDER BY m.allTimeBest30Score DESC, m.allTimeBest30At ASC")
    List<Member> findAllTimeBest30Ranking(Pageable pageable);

    // 내 주간 30곡 순위 (나보다 높은 점수 가진 사람 수)
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyBest30Score > :score")
    long countMembersWithHigherWeeklyBest30Score(int score);

    // 내 월간 30곡 순위
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.monthlyBest30Score > :score")
    long countMembersWithHigherMonthlyBest30Score(int score);

    // 내 역대 30곡 순위
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.allTimeBest30Score > :score")
    long countMembersWithHigherAllTimeBest30Score(int score);

    // 주간 30곡 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.weeklyBest30Score IS NOT NULL")
    long countWeeklyBest30Participants();

    // 월간 30곡 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.monthlyBest30Score IS NOT NULL")
    long countMonthlyBest30Participants();

    // 역대 30곡 참여자 수
    @Query("SELECT COUNT(m) FROM Member m WHERE m.status = 'ACTIVE' AND m.allTimeBest30Score IS NOT NULL")
    long countAllTimeBest30Participants();

    // ========== 관리자용 조회 ==========

    // 권한별 조회
    Page<Member> findByRole(Member.MemberRole role, Pageable pageable);

    // 상태별 회원 수
    long countByStatus(Member.MemberStatus status);

    // 기간별 가입자 수 (배치용)
    long countByCreatedAtBetween(java.time.LocalDateTime start, java.time.LocalDateTime end);

    // 권한별 회원 수
    long countByRole(Member.MemberRole role);

    // ========== 배치용 조회 ==========

    // LP Decay 대상: 활성 회원 중 멀티게임 참여 이력이 있고, 마지막 게임이 특정 날짜 이전인 회원
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.multiGames > 0 " +
           "AND m.lastGamePlayedAt IS NOT NULL AND m.lastGamePlayedAt < :threshold")
    List<Member> findMembersForLpDecay(@org.springframework.data.repository.query.Param("threshold") java.time.LocalDateTime threshold);

    // 연속 로그인 리셋 대상: 마지막 로그인 날짜가 어제가 아닌 회원 (스트릭 끊김)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.loginStreak > 0 " +
           "AND (m.lastLoginDate IS NULL OR m.lastLoginDate < :yesterday)")
    List<Member> findMembersToResetLoginStreak(@org.springframework.data.repository.query.Param("yesterday") java.time.LocalDate yesterday);

    // 휴면 전환 대상: ACTIVE 상태, 관리자 제외, 마지막 로그인이 threshold 이전 (배치용)
    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.role <> 'ADMIN' " +
           "AND ((m.lastLoginAt IS NOT NULL AND m.lastLoginAt < :threshold) " +
           "OR (m.lastLoginAt IS NULL AND m.createdAt < :threshold))")
    List<Member> findInactiveMembers(@org.springframework.data.repository.query.Param("threshold") java.time.LocalDateTime threshold);

    // ===== 로그인 실패 제한 =====
    // 확인과 증가를 한 UPDATE 로 묶는다 — 따로 하면 동시에 몰린 요청이 모두 "아직 5회 미만" 을 보고 통과한다.

    // 잠금 시간이 지난 계정은 횟수를 0 으로 되돌린다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Member m SET m.loginFailCount = 0, m.loginLockedUntil = NULL " +
           "WHERE m.email = :email AND m.loginLockedUntil IS NOT NULL AND m.loginLockedUntil <= :now")
    int releaseExpiredLoginLock(@org.springframework.data.repository.query.Param("email") String email,
                                @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    // 시도 1회를 미리 센다(성공하면 0 으로 되돌린다). 한도에 닿는 시도에서 잠금 시각을 함께 기록한다. 반환 1 = 시도 허용
    // SET 순서 주의: MariaDB 는 왼쪽부터 대입하고 뒤 항목이 바뀐 값을 본다 — 잠금 시각을 횟수보다 먼저 계산해야 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Member m SET m.loginLockedUntil = CASE WHEN m.loginFailCount + 1 >= :limit THEN :lockedUntil ELSE m.loginLockedUntil END, " +
           "m.loginFailCount = m.loginFailCount + 1 " +
           "WHERE m.email = :email AND m.status = 'ACTIVE' AND m.loginFailCount < :limit")
    int reserveLoginAttempt(@org.springframework.data.repository.query.Param("email") String email,
                            @org.springframework.data.repository.query.Param("limit") int limit,
                            @org.springframework.data.repository.query.Param("lockedUntil") java.time.LocalDateTime lockedUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Member m SET m.loginFailCount = 0, m.loginLockedUntil = NULL " +
           "WHERE m.id = :id AND (m.loginFailCount > 0 OR m.loginLockedUntil IS NOT NULL)")
    int resetLoginFailures(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("SELECT m.loginLockedUntil FROM Member m WHERE m.email = :email")
    Optional<java.time.LocalDateTime> findLoginLockedUntil(@org.springframework.data.repository.query.Param("email") String email);
}
