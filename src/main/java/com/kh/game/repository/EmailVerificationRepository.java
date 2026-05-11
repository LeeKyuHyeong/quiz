package com.kh.game.repository;

import com.kh.game.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 해당 이메일의 최신 미인증 레코드 조회 (verify-code 단계)
    Optional<EmailVerification> findFirstByEmailAndVerifiedFalseOrderByCreatedAtDesc(String email);

    // 해당 이메일의 최신 인증 완료 레코드 조회 (register 단계)
    Optional<EmailVerification> findFirstByEmailAndVerifiedTrueOrderByVerifiedAtDesc(String email);

    // 이메일별 기존 레코드 삭제 (코드 재발급 시)
    @Modifying
    @Query("DELETE FROM EmailVerification ev WHERE ev.email = :email")
    void deleteAllByEmail(@Param("email") String email);

    // 배치용: 만료된 레코드 삭제
    @Modifying
    @Query("DELETE FROM EmailVerification ev WHERE ev.expiresAt < :threshold AND ev.verified = false")
    int deleteExpiredUnverified(@Param("threshold") LocalDateTime threshold);
}
