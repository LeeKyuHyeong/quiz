package com.kh.game.service;

import com.kh.game.entity.EmailVerification;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.EmailVerificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 인증 메일에는 재발송 간격 제한이 없었다 (2026-09-19 발견) — IP 요청 제한이 유일한 방어였고,
 * 재발송할 때마다 기존 코드가 지워져 코드당 5회 시도 제한도 함께 초기화됐다.
 * 같은 주소에는 60초에 한 번만 보낸다. 기준은 DB 의 발급 시각이라 인스턴스 수·요청 제한 저장소와 무관하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("인증 메일 재발송 간격 — 같은 주소에는 60초에 한 번")
class EmailVerificationCooldownTest {

    @Autowired
    private EmailVerificationService service;
    @Autowired
    private EmailVerificationRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private BrevoMailClient mailClient;

    private final String email = "cooldown-" + System.nanoTime() + "@test.com";

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM email_verification WHERE email = ?", email);
    }

    private EmailVerification current() {
        return repository.findFirstByEmailAndVerifiedFalseOrderByCreatedAtDesc(email).orElseThrow();
    }

    @Test
    @DisplayName("60초 안의 재요청은 메일을 보내지 않고 남은 시간을 안내한다 — 기존 코드와 시도 횟수는 그대로다")
    void resendWithinCooldown_isRejected_andKeepsTheCurrentCode() {
        service.sendVerificationCode(email);
        EmailVerification first = current();
        first.setAttempts(3);
        repository.save(first);

        assertThatThrownBy(() -> service.sendVerificationCode(email))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("초 뒤");

        verify(mailClient, times(1)).send(eq(email), any(), any());
        EmailVerification after = current();
        assertThat(after.getId()).isEqualTo(first.getId());
        assertThat(after.getCode()).isEqualTo(first.getCode());
        assertThat(after.getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("60초가 지나면 새 코드로 다시 보낸다")
    void resendAfterCooldown_isAllowed() {
        service.sendVerificationCode(email);
        Long firstId = current().getId();
        jdbcTemplate.update("UPDATE email_verification SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(61)), firstId);

        service.sendVerificationCode(email);

        verify(mailClient, times(2)).send(eq(email), any(), any());
        assertThat(current().getId()).isNotEqualTo(firstId);
    }
}
