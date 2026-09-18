package com.kh.game.controller.client;

import com.kh.game.entity.Member;
import com.kh.game.entity.SongReport;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.MemberService;
import com.kh.game.service.SongReportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/song-report")
@RequiredArgsConstructor
public class SongReportController {

    private final SongReportService songReportService;
    private final MemberService memberService;

    /**
     * 곡 신고 제출
     * 회원/게스트 모두 가능
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submitReport(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession session) {

        Member member = memberService.findLoginMember(userDetails);
        // 파라미터 추출
        Long songId;
        try {
            songId = Long.valueOf(request.get("songId").toString());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "잘못된 곡 ID입니다."));
        }

        String reportTypeStr = (String) request.get("reportType");
        if (reportTypeStr == null || reportTypeStr.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "신고 유형을 선택해주세요."));
        }

        String description = (String) request.getOrDefault("description", "");

        // 신고 유형 변환
        SongReport.ReportType reportType;
        try {
            reportType = SongReport.ReportType.valueOf(reportTypeStr);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "잘못된 신고 유형입니다."));
        }

        // 세션 ID (게스트 중복 방지용)
        String sessionId = session.getId();

        Map<String, Object> result = songReportService.submitReport(songId, member, sessionId, reportType, description);

        return ResponseEntity.ok(result);
    }
}
