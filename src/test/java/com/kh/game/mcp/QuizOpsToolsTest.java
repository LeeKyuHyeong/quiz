package com.kh.game.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.entity.SongReport;
import com.kh.game.repository.McpToolAuditLogRepository;
import com.kh.game.service.BatchService;
import com.kh.game.service.MemberService;
import com.kh.game.service.SongReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 운영 도구 — 기존 서비스를 그대로 호출하고, 응답에 개인정보를 싣지 않으며,
 * 쓰기 도구는 confirm 뒤에만 실행하고, 모든 호출을 감사 로그에 남긴다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizOpsTools — MCP 운영 도구")
class QuizOpsToolsTest {

    static final String ADMIN_EMAIL = "admin@example.com";

    @Mock SongReportService songReportService;
    @Mock BatchService batchService;
    @Mock MemberService memberService;
    @Mock McpToolAuditLogRepository auditLogRepository;

    QuizOpsTools tools;
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        tools = new QuizOpsTools(songReportService, batchService, memberService, auditLogRepository, ADMIN_EMAIL);
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static Member member(long id, String email, String nickname) {
        Member m = new Member();
        m.setId(id);
        m.setEmail(email);
        m.setPassword("$2a$10$hash");
        m.setNickname(nickname);
        return m;
    }

    static SongReport report(long id, String title, SongReport.ReportStatus status) {
        Song song = new Song();
        song.setId(id * 10);
        song.setTitle(title);
        song.setArtist("artist");
        SongReport r = new SongReport(song, member(7, "reporter@example.com", "리포터"), null,
                SongReport.ReportType.UNPLAYABLE);
        r.setId(id);
        r.setStatus(status);
        return r;
    }

    @Nested
    @DisplayName("list_song_reports")
    class ListSongReports {

        @Test
        @DisplayName("status 를 주면 그 상태만 SongReportService.getReports 로 조회해 요약을 돌려준다")
        void filtersByStatus() {
            when(songReportService.getReports(eq(SongReport.ReportStatus.PENDING), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(report(1, "노래A", SongReport.ReportStatus.PENDING))));

            List<QuizOpsTools.ReportSummary> result = tools.listSongReports("PENDING");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(1L);
            assertThat(result.get(0).songTitle()).isEqualTo("노래A");
            assertThat(result.get(0).status()).isEqualTo("PENDING");
            assertThat(result.get(0).reporter()).isEqualTo("리포터");
        }

        @Test
        @DisplayName("status 가 없으면 전체를 조회한다")
        void nullStatusMeansAll() {
            when(songReportService.getReports(isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThat(tools.listSongReports(null)).isEmpty();
            verify(songReportService).getReports(isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("날짜는 모델이 읽기 쉬운 ISO 문자열이다 (Jackson 기본 배열 [2026,9,21,...] 이 아니라)")
        void datesAreIsoStrings() throws Exception {
            SongReport r = report(1, "노래A", SongReport.ReportStatus.PENDING);
            r.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 21, 22, 35, 53));
            when(songReportService.getReports(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));

            // MCP 서버의 결과 변환기는 기본 ObjectMapper(타임스탬프 배열) 로 직렬화한다 — 그 설정으로 확인
            String body = new ObjectMapper().registerModule(new JavaTimeModule())
                    .writeValueAsString(tools.listSongReports("PENDING"));

            assertThat(body).contains("\"createdAt\":\"2026-09-21T22:35:53\"");
        }

        @Test
        @DisplayName("응답 JSON 에 신고자 이메일이 없다")
        void responseHasNoEmail() throws Exception {
            when(songReportService.getReports(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(report(1, "노래A", SongReport.ReportStatus.PENDING))));

            String body = json.writeValueAsString(tools.listSongReports("PENDING"));

            assertThat(body).doesNotContain("reporter@example.com").doesNotContain("$2a$10$");
        }
    }

    @Nested
    @DisplayName("find_member")
    class FindMember {

        @Test
        @DisplayName("id·닉네임·상태·역할만 돌려주고 이메일·비밀번호 해시는 싣지 않는다")
        void returnsSafeFieldsOnly() throws Exception {
            Member m = member(3, "user@example.com", "닉네임");
            when(memberService.search(eq("닉"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(m)));

            List<QuizOpsTools.MemberSummary> result = tools.findMember("닉");
            String body = json.writeValueAsString(result);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(3L);
            assertThat(result.get(0).nickname()).isEqualTo("닉네임");
            assertThat(result.get(0).status()).isEqualTo("ACTIVE");
            assertThat(result.get(0).role()).isEqualTo("USER");
            assertThat(body).doesNotContain("user@example.com").doesNotContain("$2a$10$").doesNotContain("email");
        }
    }

    @Nested
    @DisplayName("get_batch_status")
    class GetBatchStatus {

        @Test
        @DisplayName("배치마다 활성·구현 여부·마지막 결과와 최근 이력을 묶어 돌려준다")
        void summarizesEachBatch() {
            BatchConfig cfg = new BatchConfig();
            cfg.setBatchId("lp-decay");
            cfg.setName("LP 감소");
            cfg.setEnabled(true);
            cfg.setImplemented(true);
            cfg.setScheduleText("매일 04:00");
            cfg.setLastResult(BatchConfig.ExecutionResult.SUCCESS);
            cfg.setLastResultMessage("12명");
            when(batchService.findAll()).thenReturn(List.of(cfg));
            BatchExecutionHistory h = new BatchExecutionHistory("lp-decay", "LP 감소", BatchExecutionHistory.ExecutionType.SCHEDULED);
            h.complete(BatchConfig.ExecutionResult.SUCCESS, "12명", 12, 30L);
            when(batchService.getRecentHistory("lp-decay")).thenReturn(List.of(h));

            List<QuizOpsTools.BatchSummary> result = tools.getBatchStatus();

            assertThat(result).hasSize(1);
            QuizOpsTools.BatchSummary b = result.get(0);
            assertThat(b.batchId()).isEqualTo("lp-decay");
            assertThat(b.enabled()).isTrue();
            assertThat(b.implemented()).isTrue();
            assertThat(b.lastResult()).isEqualTo("SUCCESS");
            assertThat(b.recentHistory()).hasSize(1);
            assertThat(b.recentHistory().get(0).result()).isEqualTo("SUCCESS");
            assertThat(b.recentHistory().get(0).executionType()).isEqualTo("SCHEDULED");
        }
    }

    @Nested
    @DisplayName("process_song_report (쓰기)")
    class ProcessSongReport {

        @Test
        @DisplayName("confirm=false 면 처리하지 않고 무엇을 할지만 돌려준다")
        void dryRunWithoutConfirm() {
            when(songReportService.findById(1L)).thenReturn(Optional.of(report(1, "노래A", SongReport.ReportStatus.PENDING)));

            Map<String, Object> result = tools.processSongReport(1L, "CONFIRMED", "확인함", false);

            assertThat(result.get("executed")).isEqualTo(false);
            assertThat(result.get("currentStatus")).isEqualTo("PENDING");
            assertThat(result.get("newStatus")).isEqualTo("CONFIRMED");
            assertThat((String) result.get("message")).contains("confirm");
            verify(songReportService, never()).processReport(anyLong(), any(), anyString(), any());
        }

        @Test
        @DisplayName("confirm=true 면 설정된 관리자 계정으로 SongReportService.processReport 를 호출한다")
        void executesWithConfirm() {
            Member admin = member(1, ADMIN_EMAIL, "관리자");
            admin.setRole(Member.MemberRole.ADMIN);
            when(memberService.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(admin));
            when(songReportService.processReport(1L, SongReport.ReportStatus.CONFIRMED, "확인함", admin))
                    .thenReturn(Map.of("success", true, "message", "신고가 처리되었습니다."));

            Map<String, Object> result = tools.processSongReport(1L, "CONFIRMED", "확인함", true);

            assertThat(result.get("executed")).isEqualTo(true);
            assertThat(result.get("success")).isEqualTo(true);
            verify(songReportService).processReport(1L, SongReport.ReportStatus.CONFIRMED, "확인함", admin);
        }

        @Test
        @DisplayName("설정된 관리자 계정이 ADMIN 이 아니거나 없으면 실행하지 않는다")
        void refusesWithoutAdminAccount() {
            when(memberService.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(member(1, ADMIN_EMAIL, "일반")));

            Map<String, Object> result = tools.processSongReport(1L, "CONFIRMED", "확인함", true);

            assertThat(result.get("executed")).isEqualTo(false);
            assertThat((String) result.get("message")).contains("관리자");
            verify(songReportService, never()).processReport(anyLong(), any(), anyString(), any());
        }

        @Test
        @DisplayName("잘못된 상태값은 실행하지 않고 안내한다")
        void rejectsUnknownStatus() {
            Map<String, Object> result = tools.processSongReport(1L, "WHATEVER", "", true);

            assertThat(result.get("executed")).isEqualTo(false);
            assertThat((String) result.get("message")).contains("상태");
            verify(songReportService, never()).processReport(anyLong(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("감사 로그")
    class AuditLog {

        @Test
        @DisplayName("읽기 도구 호출도 도구명·인자·호출 주체와 함께 저장된다")
        void readCallIsAudited() {
            when(memberService.search(anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            tools.findMember("abc");

            ArgumentCaptor<McpToolAuditLog> captor = ArgumentCaptor.forClass(McpToolAuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            McpToolAuditLog log = captor.getValue();
            assertThat(log.getToolName()).isEqualTo("find_member");
            assertThat(log.getArguments()).contains("abc");
            assertThat(log.getCaller()).isEqualTo(ADMIN_EMAIL);
            assertThat(log.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("쓰기 도구의 dry-run 과 실행은 executed 값으로 구분해 저장된다")
        void writeCallRecordsExecuted() {
            when(songReportService.findById(1L)).thenReturn(Optional.of(report(1, "노래A", SongReport.ReportStatus.PENDING)));

            tools.processSongReport(1L, "CONFIRMED", "메모", false);

            ArgumentCaptor<McpToolAuditLog> captor = ArgumentCaptor.forClass(McpToolAuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getToolName()).isEqualTo("process_song_report");
            assertThat(captor.getValue().getResult()).contains("\"executed\":false");
        }
    }
}
