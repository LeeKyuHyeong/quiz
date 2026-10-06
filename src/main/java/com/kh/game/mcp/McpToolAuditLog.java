package com.kh.game.mcp;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * MCP 도구 호출 감사 로그 — 어떤 도구를 어떤 인자로 누가 불렀고 결과가 무엇이었는지.
 * 읽기 도구도 남긴다(운영 조회 흔적). 쓰기 도구는 dry-run 과 실행을 result 의 executed 값으로 구분한다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "mcp_tool_audit_log")
public class McpToolAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String toolName;

    @Column(length = 1000)
    private String arguments;

    @Column(length = 1000)
    private String result;

    @Column(nullable = false)
    private boolean success;

    /** 호출 주체 식별값 — SSE 전송에는 로그인 주체가 없어 설정된 관리자 이메일(quiz.mcp.admin-email)을 적는다 */
    @Column(nullable = false, length = 100)
    private String caller;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime calledAt;

    public McpToolAuditLog(String toolName, String arguments, String caller) {
        this.toolName = toolName;
        this.arguments = truncate(arguments);
        this.caller = caller;
    }

    public void complete(boolean success, String result) {
        this.success = success;
        this.result = truncate(result);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
