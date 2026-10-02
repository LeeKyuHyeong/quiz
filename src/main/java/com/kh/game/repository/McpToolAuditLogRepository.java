package com.kh.game.repository;

import com.kh.game.mcp.McpToolAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolAuditLogRepository extends JpaRepository<McpToolAuditLog, Long> {
}
