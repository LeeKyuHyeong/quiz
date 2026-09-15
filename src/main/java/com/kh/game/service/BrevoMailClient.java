package com.kh.game.service;

import com.kh.game.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Brevo Transactional Email API 호출 (HTTPS).
 * 호스팅사가 SMTP 포트(25/465/587)를 차단하므로 API 로 보낸다.
 *
 * 엔드포인트: POST {brevoBaseUrl}/smtp/email
 * 헤더: api-key, accept, content-type
 * 본문(JSON): { "sender": {name,email}, "to": [{email}], "subject", "htmlContent" }
 * 성공: HTTP 201, { "messageId": "..." } / 실패: 4xx { code, message } / 5xx 일시 장애
 */
@Slf4j
@Component
public class BrevoMailClient {

    @Value("${app.mail.brevo.api-key:}")
    private String brevoApiKey;

    @Value("${app.mail.brevo.base-url:https://api.brevo.com/v3}")
    private String brevoBaseUrl;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.from-name:Song Quiz}")
    private String mailFromName;

    public void send(String toEmail, String subject, String htmlContent) {
        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            log.error("[Mail] BREVO_API_KEY 환경변수가 설정되지 않았습니다.");
            throw new BusinessException("이메일 발송 설정이 완료되지 않았습니다. 관리자에게 문의해주세요.");
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            log.error("[Mail] MAIL_FROM 환경변수가 설정되지 않았습니다.");
            throw new BusinessException("이메일 발송 설정이 완료되지 않았습니다. 관리자에게 문의해주세요.");
        }

        Map<String, Object> body = Map.of(
                "sender", Map.of("name", mailFromName, "email", mailFrom),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "htmlContent", htmlContent
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
                        String errorBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.error("[Mail] Brevo 4xx 응답: status={}, body={}, email={}",
                                res.getStatusCode(), errorBody, maskEmail(toEmail));
                        throw new BusinessException("이메일 발송 요청이 거절되었습니다.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.warn("[Mail] Brevo 5xx 응답: status={}, email={}",
                                res.getStatusCode(), maskEmail(toEmail));
                        throw new BusinessException("메일 서버 일시 장애입니다. 잠시 후 다시 시도해주세요.");
                    })
                    .body(Map.class);

            String messageId = (response != null) ? String.valueOf(response.get("messageId")) : "null";
            log.info("[Mail] Brevo 메일 발송 성공: email={}, subject={}, messageId={}",
                    maskEmail(toEmail), subject, messageId);
        } catch (RestClientException e) {
            log.error("[Mail] Brevo API 호출 실패: email={}", maskEmail(toEmail), e);
            throw new BusinessException("이메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    public static String maskEmail(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
