package com.kh.game.mcp;

import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.entity.SongReport;
import com.kh.game.repository.McpToolAuditLogRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.repository.SongRepository;
import com.kh.game.repository.SongReportRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 서버가 실제 전송(SSE)으로 운영 도구를 노출하는지 — MCP 자바 SDK 클라이언트로 tools/list·tools/call 을 보낸다.
 * 운영(prod)처럼 spring.ai.mcp.server.enabled=false 면 /sse 자체가 없다.
 */
@DisplayName("MCP 서버 — 운영 도구 노출")
class McpServerToolsTest {

    static final String ADMIN_EMAIL = "mcp-admin@example.com";

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.ai.mcp.server.enabled=true", "quiz.mcp.admin-email=" + ADMIN_EMAIL,
                    // 열린 SSE 연결이 활성 요청으로 잡혀 Tomcat graceful shutdown(기본 30초)이 JVM 종료를 붙든다 — 테스트만 단축
                    "spring.lifecycle.timeout-per-shutdown-phase=2s"})
    @ActiveProfiles("test")
    @DisplayName("켜짐(dev) — 도구 4개가 보이고 호출된다")
    class Enabled {

        @LocalServerPort int port;
        @Autowired SongRepository songRepository;
        @Autowired SongReportRepository songReportRepository;
        @Autowired MemberRepository memberRepository;
        @Autowired McpToolAuditLogRepository auditLogRepository;

        McpSyncClient client;
        Member reporter;
        Member admin;
        Song song;
        SongReport report;

        @BeforeEach
        void connect() {
            reporter = memberRepository.save(member("reporter-mcp@example.com", "mcp리포터", Member.MemberRole.USER));
            admin = memberRepository.save(member(ADMIN_EMAIL, "mcp관리자", Member.MemberRole.ADMIN));
            song = new Song();
            song.setTitle("MCP 테스트곡");
            song.setArtist("가수");
            song = songRepository.save(song);
            report = songReportRepository.save(new SongReport(song, reporter, null, SongReport.ReportType.UNPLAYABLE));

            client = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                    .requestTimeout(Duration.ofSeconds(10))
                    .build();
            client.initialize();
        }

        @AfterEach
        void cleanUp() {
            client.closeGracefully();
            auditLogRepository.deleteAll();
            songReportRepository.delete(report);
            songRepository.delete(song);
            memberRepository.delete(reporter);
            memberRepository.delete(admin);
        }

        @Test
        @DisplayName("tools/list 에 읽기 3개 + 쓰기 1개가 있다")
        void listsFourTools() {
            List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(names).containsExactlyInAnyOrder(
                    "list_song_reports", "get_batch_status", "find_member", "process_song_report");
        }

        @Test
        @DisplayName("list_song_reports(PENDING) 가 저장된 신고를 곡 제목·신고자 닉네임과 함께 돌려주고 이메일은 없다")
        void listSongReportsOverTransport() {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("list_song_reports", Map.of("status", "PENDING")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = text(result);
            assertThat(text).contains("MCP 테스트곡").contains("mcp리포터").contains("\"id\":" + report.getId());
            assertThat(text).doesNotContain("reporter-mcp@example.com");
        }

        @Test
        @DisplayName("process_song_report 는 confirm 없이는 상태를 바꾸지 않고, confirm=true 면 바꾸고 감사 로그 2건이 남는다")
        void processSongReportNeedsConfirm() {
            McpSchema.CallToolResult dryRun = client.callTool(new McpSchema.CallToolRequest("process_song_report",
                    Map.of("reportId", report.getId(), "newStatus", "CONFIRMED", "adminNote", "mcp", "confirm", false)));
            assertThat(text(dryRun)).contains("\"executed\":false");
            assertThat(songReportRepository.findById(report.getId()).orElseThrow().getStatus())
                    .isEqualTo(SongReport.ReportStatus.PENDING);

            McpSchema.CallToolResult run = client.callTool(new McpSchema.CallToolRequest("process_song_report",
                    Map.of("reportId", report.getId(), "newStatus", "CONFIRMED", "adminNote", "mcp", "confirm", true)));
            assertThat(text(run)).contains("\"executed\":true");
            SongReport after = songReportRepository.findById(report.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(SongReport.ReportStatus.CONFIRMED);
            assertThat(after.getAdminNote()).isEqualTo("mcp");

            List<McpToolAuditLog> logs = auditLogRepository.findAll();
            assertThat(logs).hasSize(2);
            assertThat(logs).allMatch(l -> l.getToolName().equals("process_song_report") && l.getCaller().equals(ADMIN_EMAIL));
            assertThat(logs.get(0).getResult()).contains("\"executed\":false");
            assertThat(logs.get(1).getResult()).contains("\"executed\":true");
        }

        static String text(McpSchema.CallToolResult result) {
            return result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .reduce("", String::concat);
        }

        static Member member(String email, String nickname, Member.MemberRole role) {
            Member m = new Member();
            m.setEmail(email);
            m.setPassword("$2a$10$test");
            m.setNickname(nickname);
            m.setUsername(nickname);
            m.setRole(role);
            return m;
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.ai.mcp.server.enabled=false")
    @ActiveProfiles("test")
    @DisplayName("꺼짐(prod 기본값) — /sse 가 없다")
    class Disabled {

        @Autowired TestRestTemplate rest;

        @Test
        void sseEndpointIsAbsent() {
            ResponseEntity<String> response = rest.getForEntity("/sse", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
