package com.kh.game.service;

import com.kh.game.entity.EmailVerification;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.EmailVerificationRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.util.SecurityInputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 이메일 인증 서비스
 * - 6자리 코드 발급 → Brevo Transactional Email API 호출 (HTTPS)
 * - 코드 검증 (만료/시도 횟수 제한)
 * - 인증 완료 후 10분 내 회원가입 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;

    @Value("${app.mail.brevo.api-key:}")
    private String brevoApiKey;

    @Value("${app.mail.brevo.base-url:https://api.brevo.com/v3}")
    private String brevoBaseUrl;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.from-name:Song Quiz}")
    private String mailFromName;

    private static final int CODE_TTL_MINUTES = 5;
    public static final int VERIFICATION_VALID_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public void sendVerificationCode(String email) {
        SecurityInputValidator.validateEmailOrThrow(email);

        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException("이미 가입된 이메일입니다.");
        }

        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            log.error("[EmailVerify] BREVO_API_KEY 환경변수가 설정되지 않았습니다.");
            throw new BusinessException("이메일 발송 설정이 완료되지 않았습니다. 관리자에게 문의해주세요.");
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            log.error("[EmailVerify] MAIL_FROM 환경변수가 설정되지 않았습니다.");
            throw new BusinessException("이메일 발송 설정이 완료되지 않았습니다. 관리자에게 문의해주세요.");
        }

        verificationRepository.deleteAllByEmail(email);

        String code = generateCode();
        EmailVerification record = EmailVerification.create(email, code, CODE_TTL_MINUTES);
        verificationRepository.save(record);

        sendCodeEmail(email, code);
        log.info("[EmailVerify] 인증 코드 발송 완료: email={}", maskEmail(email));
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
        log.info("[EmailVerify] 인증 성공: email={}", maskEmail(email));
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

    /**
     * Brevo Transactional Email API 호출.
     *
     * 엔드포인트: POST {brevoBaseUrl}/smtp/email
     * 헤더: api-key, accept, content-type
     * 본문(JSON):
     *   {
     *     "sender":      { "name": "...", "email": "..." },
     *     "to":          [ { "email": "..." } ],
     *     "subject":     "...",
     *     "htmlContent": "..."
     *   }
     * 성공: HTTP 201, { "messageId": "..." }
     * 실패: 4xx { "code": "...", "message": "..." } / 5xx 일시 장애
     */
    private void sendCodeEmail(String toEmail, String code) {
        String subject = "[Song Quiz] 이메일 인증 코드";
        String html = buildHtmlBody(code);

        Map<String, Object> body = Map.of(
                "sender", Map.of("name", mailFromName, "email", mailFrom),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "htmlContent", html
        );

        RestClient client = RestClient.builder()
                .baseUrl(brevoBaseUrl)
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String errorBody = new String(
                                res.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        log.error("[EmailVerify] Brevo 4xx 응답: status={}, body={}, email={}",
                                res.getStatusCode(), errorBody, maskEmail(toEmail));
                        throw new BusinessException("이메일 발송 요청이 거절되었습니다.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.warn("[EmailVerify] Brevo 5xx 응답: status={}, email={}",
                                res.getStatusCode(), maskEmail(toEmail));
                        throw new BusinessException("메일 서버 일시 장애입니다. 잠시 후 다시 시도해주세요.");
                    })
                    .body(Map.class);

            String messageId = (response != null) ? String.valueOf(response.get("messageId")) : "null";
            log.info("[EmailVerify] Brevo 메일 발송 성공: email={}, messageId={}",
                    maskEmail(toEmail), messageId);
        } catch (RestClientException e) {
            log.error("[EmailVerify] Brevo API 호출 실패: email={}", maskEmail(toEmail), e);
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
