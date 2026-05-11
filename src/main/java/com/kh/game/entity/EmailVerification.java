package com.kh.game.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 회원가입 이메일 인증 레코드
 *
 * 흐름:
 * 1. /auth/send-verification → 새 레코드 생성 (verified=false, expiresAt=now+5min)
 * 2. /auth/verify-code → 코드 매칭 시 verified=true, verifiedAt=now
 * 3. /auth/register → verifiedAt이 최근 10분 이내인 인증된 레코드 확인 후 회원가입 진행
 * 4. 회원가입 완료 시 해당 레코드 삭제 (또는 만료된 것은 배치로 정리)
 */
@Entity
@Table(name = "email_verification", indexes = {
        @Index(name = "idx_email_verification_email", columnList = "email"),
        @Index(name = "idx_email_verification_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isVerificationFresh(int validForMinutes) {
        return verified
                && verifiedAt != null
                && verifiedAt.isAfter(LocalDateTime.now().minusMinutes(validForMinutes));
    }

    public static EmailVerification create(String email, String code, int ttlMinutes) {
        EmailVerification v = new EmailVerification();
        v.email = email;
        v.code = code;
        v.expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
        return v;
    }
}
