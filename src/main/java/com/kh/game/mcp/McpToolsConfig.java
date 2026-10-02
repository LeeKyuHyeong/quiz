package com.kh.game.mcp;

import com.kh.game.repository.McpToolAuditLogRepository;
import com.kh.game.service.BatchService;
import com.kh.game.service.MemberService;
import com.kh.game.service.SongReportService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 운영 도구 등록. spring.ai.mcp.server.enabled=true 일 때만 빈이 생긴다 — 기본값은 false(application.properties),
 * dev 프로파일만 true. 관리자 기능을 HTTP(/sse, /mcp/message)로 여는 셈이라 운영에는 켜지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpToolsConfig {

    @Bean
    public QuizOpsTools quizOpsTools(SongReportService songReportService, BatchService batchService,
                                     MemberService memberService, McpToolAuditLogRepository auditLogRepository,
                                     @Value("${quiz.mcp.admin-email:}") String adminEmail) {
        return new QuizOpsTools(songReportService, batchService, memberService, auditLogRepository, adminEmail);
    }

    @Bean
    public ToolCallbackProvider quizOpsToolCallbackProvider(QuizOpsTools quizOpsTools) {
        return MethodToolCallbackProvider.builder().toolObjects(quizOpsTools).build();
    }
}
