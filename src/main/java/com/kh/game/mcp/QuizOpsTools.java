package com.kh.game.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kh.game.entity.BatchConfig;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Member;
import com.kh.game.entity.SongReport;
import com.kh.game.repository.McpToolAuditLogRepository;
import com.kh.game.service.BatchService;
import com.kh.game.service.MemberService;
import com.kh.game.service.SongReportService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Claude Code 등 MCP 클라이언트에 여는 quiz 운영 도구. 새 쿼리 없이 기존 서비스를 그대로 부른다.
 * 응답에는 이메일·비밀번호 해시를 싣지 않는다. 쓰기 도구는 confirm=true 일 때만 실행한다.
 * 빈 등록은 {@link McpToolsConfig}, 노출 여부는 spring.ai.mcp.server.enabled(기본 false, dev 만 true).
 */
public class QuizOpsTools {

    static final int PAGE_SIZE = 20;

    private final SongReportService songReportService;
    private final BatchService batchService;
    private final MemberService memberService;
    private final McpToolAuditLogRepository auditLogRepository;
    private final String adminEmail;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public QuizOpsTools(SongReportService songReportService, BatchService batchService, MemberService memberService,
                        McpToolAuditLogRepository auditLogRepository, String adminEmail) {
        this.songReportService = songReportService;
        this.batchService = batchService;
        this.memberService = memberService;
        this.auditLogRepository = auditLogRepository;
        this.adminEmail = adminEmail;
    }

    public record ReportSummary(Long id, Long songId, String songTitle, String artist, String reportType,
                                String status, String description, String reporter, String createdAt) {}

    public record MemberSummary(Long id, String nickname, String status, String role, String createdAt) {}

    public record HistorySummary(String executionType, String result, String message, Integer affectedCount,
                                 Long executionTimeMs, String executedAt) {}

    public record BatchSummary(String batchId, String name, boolean enabled, boolean implemented, String scheduleText,
                               String lastResult, String lastResultMessage, String lastExecutedAt,
                               List<HistorySummary> recentHistory) {}

    @Tool(name = "list_song_reports",
            description = "곡 신고 목록(최신순, 최대 20건). status 는 PENDING·CONFIRMED·REJECTED·RESOLVED 중 하나, 비우면 전체")
    public List<ReportSummary> listSongReports(
            @ToolParam(required = false, description = "신고 상태 (PENDING, CONFIRMED, REJECTED, RESOLVED). 비우면 전체") String status) {
        return audited("list_song_reports", Map.of("status", String.valueOf(status)), () -> {
            SongReport.ReportStatus filter = (status == null || status.isBlank()) ? null
                    : SongReport.ReportStatus.valueOf(status.trim().toUpperCase());
            return songReportService.getReports(filter, PageRequest.of(0, PAGE_SIZE))
                    .map(QuizOpsTools::toSummary).getContent();
        });
    }

    @Tool(name = "get_batch_status", description = "배치 전체(24종)의 활성·구현 여부, 마지막 실행 결과, 최근 실행 이력")
    public List<BatchSummary> getBatchStatus() {
        return audited("get_batch_status", Map.of(), () -> batchService.findAll().stream()
                .map(cfg -> new BatchSummary(cfg.getBatchId(), cfg.getName(),
                        Boolean.TRUE.equals(cfg.getEnabled()), Boolean.TRUE.equals(cfg.getImplemented()),
                        cfg.getScheduleText(), name(cfg.getLastResult()), cfg.getLastResultMessage(),
                        iso(cfg.getLastExecutedAt()),
                        batchService.getRecentHistory(cfg.getBatchId()).stream().limit(3)
                                .map(QuizOpsTools::toSummary).toList()))
                .toList());
    }

    @Tool(name = "find_member", description = "회원 검색(닉네임 또는 이메일 포함 검색, 최대 20명). id·닉네임·상태·역할만 돌려준다")
    public List<MemberSummary> findMember(@ToolParam(description = "검색어 — 닉네임 또는 이메일의 일부") String keyword) {
        return audited("find_member", Map.of("keyword", keyword), () ->
                memberService.search(keyword, PageRequest.of(0, PAGE_SIZE))
                        .map(m -> new MemberSummary(m.getId(), m.getNickname(), name(m.getStatus()), name(m.getRole()),
                                iso(m.getCreatedAt())))
                        .getContent());
    }

    @Tool(name = "process_song_report",
            description = "곡 신고 처리(쓰기). confirm=false 면 실행하지 않고 무엇을 바꿀지만 보여준다. "
                    + "실제 처리는 사용자에게 확인을 받은 뒤 confirm=true 로 다시 부른다")
    public Map<String, Object> processSongReport(
            @ToolParam(description = "신고 id") Long reportId,
            @ToolParam(description = "바꿀 상태 (CONFIRMED, REJECTED, RESOLVED)") String newStatus,
            @ToolParam(required = false, description = "관리자 메모") String adminNote,
            @ToolParam(description = "true 일 때만 실제로 처리한다") boolean confirm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reportId", reportId);
        args.put("newStatus", newStatus);
        args.put("adminNote", adminNote);
        args.put("confirm", confirm);
        return audited("process_song_report", args, () -> doProcessSongReport(reportId, newStatus, adminNote, confirm));
    }

    private Map<String, Object> doProcessSongReport(Long reportId, String newStatus, String adminNote, boolean confirm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executed", false);
        result.put("reportId", reportId);
        result.put("newStatus", newStatus);

        SongReport.ReportStatus status;
        try {
            status = SongReport.ReportStatus.valueOf(newStatus == null ? "" : newStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            result.put("message", "잘못된 상태값입니다. CONFIRMED, REJECTED, RESOLVED 중 하나여야 합니다.");
            return result;
        }
        String note = adminNote == null ? "" : adminNote;

        if (!confirm) {
            Optional<SongReport> report = songReportService.findById(reportId);
            if (report.isEmpty()) {
                result.put("message", "신고를 찾을 수 없습니다.");
                return result;
            }
            result.put("currentStatus", name(report.get().getStatus()));
            result.put("songTitle", report.get().getSong().getTitle());
            result.put("message", "아직 처리하지 않았습니다. 사용자에게 확인을 받은 뒤 confirm=true 로 다시 호출하면 "
                    + "신고 " + reportId + " 을(를) " + status.name() + " 으로 처리합니다.");
            return result;
        }

        Member admin = memberService.findByEmail(adminEmail == null ? "" : adminEmail)
                .filter(m -> m.getRole() == Member.MemberRole.ADMIN).orElse(null);
        if (admin == null) {
            result.put("message", "quiz.mcp.admin-email 에 설정된 관리자 계정이 없거나 ADMIN 이 아니라 처리할 수 없습니다.");
            return result;
        }

        Map<String, Object> processed = songReportService.processReport(reportId, status, note, admin);
        result.put("executed", true);
        result.putAll(processed);
        return result;
    }

    private <T> T audited(String toolName, Map<String, ?> arguments, Supplier<T> call) {
        McpToolAuditLog log = new McpToolAuditLog(toolName, toJson(arguments), callerId());
        try {
            T result = call.get();
            log.complete(true, toJson(result));
            return result;
        } catch (RuntimeException e) {
            log.complete(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            auditLogRepository.save(log);
        }
    }

    private String callerId() {
        return (adminEmail == null || adminEmail.isBlank()) ? "unconfigured" : adminEmail;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static ReportSummary toSummary(SongReport r) {
        return new ReportSummary(r.getId(), r.getSong().getId(), r.getSong().getTitle(), r.getSong().getArtist(),
                name(r.getReportType()), name(r.getStatus()), r.getDescription(),
                r.getMember() != null ? r.getMember().getNickname() : "guest", iso(r.getCreatedAt()));
    }

    private static HistorySummary toSummary(BatchExecutionHistory h) {
        return new HistorySummary(name(h.getExecutionType()), name(h.getResult()), h.getMessage(),
                h.getAffectedCount(), h.getExecutionTimeMs(), iso(h.getExecutedAt()));
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    /** 날짜는 ISO 문자열로 — MCP 서버의 기본 직렬화(타임스탬프 배열)보다 모델이 읽기 쉽다 */
    private static String iso(LocalDateTime t) {
        return t == null ? null : t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
