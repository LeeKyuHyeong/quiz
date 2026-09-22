package com.kh.game.security;

import com.kh.game.entity.EmailVerification;
import com.kh.game.entity.Member;
import com.kh.game.entity.MemberLoginHistory;
import com.kh.game.repository.EmailVerificationRepository;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 로그인 요청(/auth/login-process)에는 요청 제한도 실패 횟수 제한도 없었다 (2026-09-19 발견).
 * 요청 제한은 화면이 먼저 부르는 /auth/check-login 에만 걸려 있어, 로그인 요청을 직접 보내면 비밀번호를 무제한 대입할 수 있었다.
 * IP 제한은 IP 를 바꾸면 뚫리므로, 계정 기준 연속 실패 제한을 함께 둔다.
 *
 * 요청 제한기의 시계는 이 테스트가 돌린다 — 실제 시계면 느린 PC 에서 로그인 20회가 3초를 넘길 때 보충된 토큰으로
 * 21번째가 통과해 결과가 기계 속도에 좌우됐다(2026-09-22 O-023, 회사 PC 3.28·3.42초).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("로그인 요청 제한 — IP 당 분당 20회, 계정당 연속 5회 실패 시 5분 잠금")
class LoginAttemptLimitTest {

    /** 손으로 돌리는 시계. 테스트가 advance 하기 전엔 멈춰 있어 버킷이 보충되지 않는다. */
    static final class ManualClock implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    static final ManualClock CLOCK = new ManualClock();

    @TestConfiguration
    static class ManualClockConfig {
        /** 운영 빈(@Component, 실제 시계) 대신 이 시계를 보는 제한기를 필터·컨트롤러에 넣는다 */
        @Bean
        @Primary
        LoginRateLimiter manualClockLoginRateLimiter() {
            return new LoginRateLimiter(CLOCK);
        }
    }

    private static final String PASSWORD = "Passw0rd!limit";
    private static final String WRONG = "wrong-password";
    private static final String GENERIC = "이메일 또는 비밀번호가 일치하지 않습니다.";
    private static final String LOCKED = "로그인이 잠겼습니다";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberLoginHistoryRepository loginHistoryRepository;
    @Autowired
    private EmailVerificationRepository verificationRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            loginHistoryRepository.deleteAll(loginHistoryRepository
                    .findByMemberIdOrderByCreatedAtDesc(id, Pageable.unpaged()).getContent());
            memberRepository.deleteById(id);
        }
    }

    private Member createMember() {
        Member member = new Member();
        member.setEmail("limit-" + System.nanoTime() + "@test.com");
        member.setPassword(passwordEncoder.encode(PASSWORD));
        member.setNickname("limit" + (System.nanoTime() % 1000000));
        member.setUsername("limit");
        member.setRole(Member.MemberRole.USER);
        member.setStatus(Member.MemberStatus.ACTIVE);
        Member saved = memberRepository.save(member);
        createdIds.add(saved.getId());
        return saved;
    }

    /** MockMvc 의 기본 접속 주소(127.0.0.1)는 요청 제한 화이트리스트라, 제한을 보려면 외부 주소로 보낸다. */
    private ResultActions login(String email, String password, String remoteAddr) throws Exception {
        return mockMvc.perform(post("/auth/login-process").with(csrf())
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                })
                .param("email", email)
                .param("password", password));
    }

    private void failTimes(Member member, int times, String remoteAddr) throws Exception {
        for (int i = 0; i < times; i++) {
            login(member.getEmail(), WRONG, remoteAddr)
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }
    }

    private int failCount(Member member) {
        return jdbcTemplate.queryForObject("SELECT login_fail_count FROM member WHERE id = ?",
                Integer.class, member.getId());
    }

    @Test
    @DisplayName("같은 IP 의 로그인 요청은 분당 20회까지만 받고, 그 뒤는 429 와 안내 문구를 돌려준다 — 1분이 지나면 다시 받는다")
    void loginRequests_areRateLimitedPerIp() throws Exception {
        String ip = "203.0.113.21";
        for (int i = 0; i < 20; i++) {
            login("nobody-" + i + "@test.com", WRONG, ip).andExpect(status().isOk());
        }
        login("nobody-last@test.com", WRONG, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("요청이 너무 잦습니다")));

        // 보충은 greedy(분당 20 = 3초마다 1개): 2초 뒤엔 아직 0개, 4초 뒤엔 1개
        CLOCK.advance(Duration.ofSeconds(2));
        login("nobody-still@test.com", WRONG, ip).andExpect(status().isTooManyRequests());

        CLOCK.advance(Duration.ofSeconds(2));
        login("nobody-again@test.com", WRONG, ip).andExpect(status().isOk());
        login("nobody-again2@test.com", WRONG, ip).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("한 계정에 연속 5회 실패하면 올바른 비밀번호로도 로그인이 거부되고 잠금 안내가 나온다")
    void fiveConsecutiveFailures_lockTheAccount() throws Exception {
        Member member = createMember();
        failTimes(member, 5, "203.0.113.22");

        login(member.getEmail(), PASSWORD, "203.0.113.22")
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString(LOCKED)));
    }

    @Test
    @DisplayName("IP 를 바꿔 가며 시도해도 계정 기준으로 잠긴다")
    void lockIsPerAccount_notPerIp() throws Exception {
        Member member = createMember();
        for (int i = 0; i < 5; i++) {
            failTimes(member, 1, "203.0.113." + (30 + i));
        }

        login(member.getEmail(), PASSWORD, "203.0.113.39")
                .andExpect(jsonPath("$.message").value(containsString(LOCKED)));
    }

    @Test
    @DisplayName("잠금 시간이 지나면 다시 로그인할 수 있다")
    void lockExpires() throws Exception {
        Member member = createMember();
        failTimes(member, 5, "203.0.113.23");
        jdbcTemplate.update("UPDATE member SET login_locked_until = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), member.getId());

        login(member.getEmail(), PASSWORD, "203.0.113.23")
                .andExpect(jsonPath("$.success").value(true));
        assertThat(failCount(member)).isZero();
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 횟수가 0 이 된다 — 4회 실패·성공·4회 실패는 잠기지 않는다")
    void successResetsTheCounter() throws Exception {
        Member member = createMember();
        failTimes(member, 4, "203.0.113.24");
        login(member.getEmail(), PASSWORD, "203.0.113.24").andExpect(jsonPath("$.success").value(true));
        assertThat(failCount(member)).isZero();

        failTimes(member, 4, "203.0.113.25");
        login(member.getEmail(), PASSWORD, "203.0.113.25").andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("비밀번호 재설정에 성공하면 잠금이 바로 풀린다")
    void passwordReset_unlocks() throws Exception {
        Member member = createMember();
        failTimes(member, 5, "203.0.113.26");

        EmailVerification verified = EmailVerification.create(member.getEmail(), "123456", 5);
        verified.setVerified(true);
        verified.setVerifiedAt(LocalDateTime.now());
        verificationRepository.save(verified);
        String newPassword = "N3w!password";
        mockMvc.perform(post("/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + member.getEmail() + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(jsonPath("$.success").value(true));

        login(member.getEmail(), newPassword, "203.0.113.26")
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("정지(BANNED) 계정은 몇 번을 시도해도 정지 안내만 받는다 — 일시 잠금 안내와 섞이지 않는다")
    void bannedAccount_keepsItsOwnMessage() throws Exception {
        Member member = createMember();
        member.setStatus(Member.MemberStatus.BANNED);
        memberRepository.save(member);

        for (int i = 0; i < 7; i++) {
            login(member.getEmail(), PASSWORD, "203.0.113.27")
                    .andExpect(jsonPath("$.message").value("정지된 계정입니다."));
        }
    }

    @Test
    @DisplayName("존재하지 않는 이메일은 몇 번을 시도해도 같은 문구만 받는다")
    void unknownEmail_neverRevealsLockState() throws Exception {
        String email = "ghost-" + System.nanoTime() + "@test.com";
        for (int i = 0; i < 7; i++) {
            login(email, WRONG, "203.0.113.28")
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }
    }

    @Test
    @DisplayName("동시에 몰아서 보내도 실패로 세는 시도는 5회를 넘지 않는다 (확인 후 증가 사이의 틈이 없다)")
    void parallelAttempts_cannotExceedTheLimit() throws Exception {
        Member member = createMember();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            String ip = "203.0.113." + (100 + i);
            pool.submit(() -> {
                try {
                    start.await();
                    String body = login(member.getEmail(), WRONG, ip).andReturn()
                            .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    messages.add(body);
                } catch (Exception e) {
                    messages.add("ERROR " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(messages).noneMatch(m -> m.startsWith("ERROR"));
        assertThat(messages.stream().filter(m -> m.contains(GENERIC)).count())
                .as("비밀번호 검사까지 간 시도").isEqualTo(5);
        assertThat(failCount(member)).isEqualTo(5);
    }

    @Test
    @DisplayName("관리자 로그인 폼(/admin/login-process)도 같은 잠금을 따른다 — 잠금을 우회하는 비밀번호 검사 경로가 아니다")
    void adminLoginForm_honorsTheLock() throws Exception {
        Member member = createMember();
        member.setRole(Member.MemberRole.ADMIN);
        memberRepository.save(member);
        failTimes(member, 5, "203.0.113.29");

        mockMvc.perform(post("/admin/login-process").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("203.0.113.29");
                    return request;
                })
                .param("username", member.getEmail())
                .param("password", PASSWORD));

        List<MemberLoginHistory> history = loginHistoryRepository
                .findByMemberIdOrderByCreatedAtDesc(member.getId(), Pageable.unpaged()).getContent();
        assertThat(history).as("잠긴 동안에는 로그인 성공 이력이 생기지 않는다").isEmpty();
    }

    @Test
    @DisplayName("관리자 로그인 폼의 실패도 같은 횟수에 합산된다")
    void adminLoginForm_failuresCount() throws Exception {
        Member member = createMember();
        for (int i = 0; i < 5; i++) {
            try {
                mockMvc.perform(post("/admin/login-process").with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.40");
                            return request;
                        })
                        .param("username", member.getEmail())
                        .param("password", WRONG));
            } catch (Exception ignored) {
                // 이 경로의 실패 응답 형식은 이 테스트의 관심사가 아니다 — 횟수만 본다
            }
        }

        login(member.getEmail(), PASSWORD, "203.0.113.41")
                .andExpect(jsonPath("$.message").value(containsString(LOCKED)));
    }
}
