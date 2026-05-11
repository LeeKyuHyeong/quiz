package com.kh.game.service;

import com.kh.game.entity.EmailVerification;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.EmailVerificationRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.util.SecurityInputValidator;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * 이메일 인증 서비스
 * - 6자리 코드 발급 → Gmail SMTP 발송
 * - 코드 검증 (만료/시도 횟수 제한)
 * - 인증 완료 후 10분 내 회원가입 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.from-name:Song Quiz}")
    private String mailFromName;

    private static final int CODE_TTL_MINUTES = 5;        // 코드 자체 만료 시간
    public static final int VERIFICATION_VALID_MINUTES = 10; // 인증 완료 후 회원가입 유효 시간
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 인증 코드 발송 — 이메일 검증 + 중복 가입 차단 + 코드 발송
     */
    @Transactional
    public void sendVerificationCode(String email) {
        SecurityInputValidator.validateEmailOrThrow(email);

        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException("이미 가입된 이메일입니다.");
        }

        if (mailFrom == null || mailFrom.isBlank()) {
            log.error("[EmailVerify] MAIL_USERNAME 환경변수가 설정되지 않았습니다.");
            throw new BusinessException("이메일 발송 설정이 완료되지 않았습니다. 관리자에게 문의해주세요.");
        }

        // 기존 레코드 정리 → 신규 코드 발급
        verificationRepository.deleteAllByEmail(email);

        String code = generateCode();
        EmailVerification record = EmailVerification.create(email, code, CODE_TTL_MINUTES);
        verificationRepository.save(record);

        sendCodeEmail(email, code);
        log.info("[EmailVerify] 인증 코드 발송 완료: email={}", maskEmail(email));
    }

    /**
     * 코드 검증
     */
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
        log.info("[EmailVerify] 인증 성공: email={}", maskEmail(email));
    }

    /**
     * 회원가입 시 호출 — 최근 인증된 상태인지 확인
     */
    public boolean isRecentlyVerified(String email) {
        Optional<EmailVerification> recordOpt =
                verificationRepository.findFirstByEmailAndVerifiedTrueOrderByVerifiedAtDesc(email);
        return recordOpt.map(r -> r.isVerificationFresh(VERIFICATION_VALID_MINUTES))
                .orElse(false);
    }

    /**
     * 회원가입 완료 후 호출 — 인증 레코드 정리
     */
    @Transactional
    public void consumeVerification(String email) {
        verificationRepository.deleteAllByEmail(email);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private void sendCodeEmail(String toEmail, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress(mailFrom, mailFromName, "UTF-8"));
            helper.setTo(toEmail);
            helper.setSubject("[Song Quiz] 이메일 인증 코드");
            helper.setText(buildHtmlBody(code), true);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("[EmailVerify] 메일 발송 실패: email={}", maskEmail(toEmail), e);
            throw new BusinessException("이메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private String buildHtmlBody(String code) {
        return """
                <div style="font-family: 'Segoe UI', sans-serif; max-width: 480px; margin: 0 auto; padding: 24px; background: #f9fafb; border-radius: 12px;">
                    <h2 style="color: #1e293b;">🎵 Song Quiz 이메일 인증</h2>
                    <p style="color: #475569; font-size: 14px;">아래 인증 코드를 회원가입 화면에 입력해주세요.</p>
                    <div style="margin: 24px 0; padding: 16px; background: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px; text-align: center;">
                        <div style="font-size: 36px; font-weight: 700; letter-spacing: 8px; color: #2563eb;">%s</div>
                    </div>
                    <p style="color: #94a3b8; font-size: 12px;">이 코드는 5분 후 만료됩니다. 본인이 요청하지 않았다면 이 메일을 무시해주세요.</p>
                </div>
                """.formatted(code);
    }

    private String maskEmail(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
