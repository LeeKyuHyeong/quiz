package com.kh.game.service;

import com.kh.game.entity.EmailVerification;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.EmailVerificationRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.util.SecurityInputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * 이메일 인증 서비스
 * - 6자리 코드 발급 → BrevoMailClient 로 발송 (회원가입 / 비밀번호 재설정 공용)
 * - 코드 검증 (만료/시도 횟수 제한)
 * - 인증 완료 후 10분 내 회원가입·비밀번호 재설정 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;

    private final BrevoMailClient mailClient;

    private static final int CODE_TTL_MINUTES = 5;
    public static final int VERIFICATION_VALID_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 회원가입용 — 아직 가입되지 않은 이메일에만 보낸다. */
    @Transactional
    public void sendVerificationCode(String email) {
        SecurityInputValidator.validateEmailOrThrow(email);

        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException("이미 가입된 이메일입니다.");
        }
        issueCode(email, "회원가입 화면");
    }

    /** 비밀번호 재설정용 — 가입된 이메일에만 보낸다. */
    @Transactional
    public void sendPasswordResetCode(String email) {
        SecurityInputValidator.validateEmailOrThrow(email);

        if (!memberRepository.existsByEmail(email)) {
            throw new BusinessException("가입되지 않은 이메일입니다.");
        }
        issueCode(email, "비밀번호 재설정 화면");
    }

    private void issueCode(String email, String screenName) {
        // 재발송 간격. 발급 시각(DB) 기준이라 IP 요청 제한과 무관하게 걸린다 — 없으면 같은 주소로 메일을 계속 보내게 할 수 있고,
        // 재발급이 기존 코드를 지우므로 코드당 시도 횟수 제한(MAX_ATTEMPTS)도 함께 초기화된다.
        verificationRepository.findFirstByEmailOrderByCreatedAtDesc(email).ifPresent(last -> {
            long elapsed = java.time.Duration.between(last.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
            if (elapsed < RESEND_COOLDOWN_SECONDS) {
                throw new BusinessException("인증 메일은 " + RESEND_COOLDOWN_SECONDS + "초에 한 번만 보낼 수 있습니다. "
                        + (RESEND_COOLDOWN_SECONDS - elapsed) + "초 뒤에 다시 시도해주세요.");
            }
        });

        verificationRepository.deleteAllByEmail(email);

        String code = generateCode();
        EmailVerification record = EmailVerification.create(email, code, CODE_TTL_MINUTES);
        verificationRepository.save(record);

        mailClient.send(email, "[Song Quiz] 이메일 인증 코드", buildHtmlBody(code, screenName));
        log.info("[EmailVerify] 인증 코드 발송 완료: email={}, purpose={}", BrevoMailClient.maskEmail(email), screenName);
    }

    @Transactional
    public void verifyCode(String email, String code) {
        SecurityInputValidator.validateEmailOrThrow(email);

        if (code == null || !code.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("6자리 숫자 코드를 입력해주세요.");
        }

        EmailVerification record = verificationRepository
                .findFirstByEmailAndVerifiedFalseOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new BusinessException("인증 코드를 먼저 요청해주세요."));

        if (record.isExpired()) {
            throw new BusinessException("인증 코드가 만료되었습니다. 다시 요청해주세요.");
        }

        if (record.getAttempts() >= MAX_ATTEMPTS) {
            throw new BusinessException("시도 횟수를 초과했습니다. 코드를 다시 요청해주세요.");
        }

        if (!record.getCode().equals(code)) {
            record.setAttempts(record.getAttempts() + 1);
            verificationRepository.save(record);
            throw new BusinessException("인증 코드가 일치하지 않습니다.");
        }

        record.setVerified(true);
        record.setVerifiedAt(java.time.LocalDateTime.now());
        verificationRepository.save(record);
        log.info("[EmailVerify] 인증 성공: email={}", BrevoMailClient.maskEmail(email));
    }

    public boolean isRecentlyVerified(String email) {
        Optional<EmailVerification> recordOpt =
                verificationRepository.findFirstByEmailAndVerifiedTrueOrderByVerifiedAtDesc(email);
        return recordOpt.map(r -> r.isVerificationFresh(VERIFICATION_VALID_MINUTES))
                .orElse(false);
    }

    @Transactional
    public void consumeVerification(String email) {
        verificationRepository.deleteAllByEmail(email);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String buildHtmlBody(String code, String screenName) {
        return """
                <div style="font-family: 'Segoe UI', sans-serif; max-width: 480px; margin: 0 auto; padding: 24px; background: #f9fafb; border-radius: 12px;">
                    <h2 style="color: #1e293b;">🎵 Song Quiz 이메일 인증</h2>
                    <p style="color: #475569; font-size: 14px;">아래 인증 코드를 %s에 입력해주세요.</p>
                    <div style="margin: 24px 0; padding: 16px; background: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px; text-align: center;">
                        <div style="font-size: 36px; font-weight: 700; letter-spacing: 8px; color: #2563eb;">%s</div>
                    </div>
                    <p style="color: #94a3b8; font-size: 12px;">이 코드는 5분 후 만료됩니다. 본인이 요청하지 않았다면 이 메일을 무시해주세요.</p>
                </div>
                """.formatted(screenName, code);
    }
}
