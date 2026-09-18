package com.kh.game.controller.client;

import com.kh.game.entity.Board;
import com.kh.game.entity.BoardComment;
import com.kh.game.entity.Member;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.BoardService;
import com.kh.game.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;
    private final MemberService memberService;

    // 게시판 목록 페이지
    @GetMapping("/board")
    public String boardList(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Member member = memberService.findLoginMember(userDetails);
        Board.BoardCategory boardCategory = null;
        if (category != null && !category.isEmpty()) {
            try {
                boardCategory = Board.BoardCategory.valueOf(category);
            } catch (Exception ignored) {}
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<Board> boardPage = boardService.getBoards(boardCategory, keyword, pageable);

        model.addAttribute("boards", boardPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", boardPage.getTotalPages());
        model.addAttribute("totalItems", boardPage.getTotalElements());
        model.addAttribute("category", category);
        model.addAttribute("keyword", keyword);
        model.addAttribute("categories", Board.BoardCategory.values());
        model.addAttribute("categoryStats", boardService.getCategoryStats());
        model.addAttribute("member", member);

        return "client/board/list";
    }

    // 게시글 상세 페이지
    @GetMapping("/board/{id}")
    public String boardDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Member member = memberService.findLoginMember(userDetails);
        Board board = boardService.findByIdAndIncrementView(id).orElse(null);
        if (board == null || board.getStatus() != Board.BoardStatus.ACTIVE) {
            return "redirect:/board";
        }

        List<BoardComment> comments = boardService.getComments(id);
        boolean isLiked = boardService.isLikedByMember(id, member);

        model.addAttribute("board", board);
        model.addAttribute("comments", comments);
        model.addAttribute("isLiked", isLiked);
        model.addAttribute("member", member);

        return "client/board/detail";
    }

    // 게시글 작성 페이지
    @GetMapping("/board/write")
    public String boardWriteForm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return "redirect:/auth/login?redirect=/board/write";
        }

        model.addAttribute("categories", Board.BoardCategory.values());
        model.addAttribute("member", member);

        return "client/board/write";
    }

    // 게시글 수정 페이지
    @GetMapping("/board/{id}/edit")
    public String boardEditForm(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return "redirect:/auth/login?redirect=/board/" + id + "/edit";
        }

        Board board = boardService.findById(id).orElse(null);
        if (board == null || !board.getMember().getId().equals(member.getId())) {
            return "redirect:/board";
        }

        model.addAttribute("board", board);
        model.addAttribute("categories", Board.BoardCategory.values());
        model.addAttribute("member", member);

        return "client/board/write";
    }

    // ========== API 엔드포인트 ==========

    // 게시글 작성
    @PostMapping("/api/board")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createBoard(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        String categoryStr = request.get("category");
        String title = request.get("title");
        String content = request.get("content");

        if (categoryStr == null || title == null || content == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "필수 항목을 입력해주세요."));
        }

        if (title.trim().length() < 2 || title.trim().length() > 100) {
            return ResponseEntity.ok(Map.of("success", false, "message", "제목은 2~100자로 입력해주세요."));
        }

        if (content.trim().length() < 10) {
            return ResponseEntity.ok(Map.of("success", false, "message", "내용은 10자 이상 입력해주세요."));
        }

        Board.BoardCategory category;
        try {
            category = Board.BoardCategory.valueOf(categoryStr);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "잘못된 카테고리입니다."));
        }

        return ResponseEntity.ok(boardService.createBoard(member, category, title, content));
    }

    // 게시글 수정
    @PutMapping("/api/board/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateBoard(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        String title = request.get("title");
        String content = request.get("content");

        if (title == null || content == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "필수 항목을 입력해주세요."));
        }

        return ResponseEntity.ok(boardService.updateBoard(id, member, title, content));
    }

    // 게시글 삭제
    @DeleteMapping("/api/board/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteBoard(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        boolean isAdmin = member.getRole() == Member.MemberRole.ADMIN;
        return ResponseEntity.ok(boardService.deleteBoard(id, member, isAdmin));
    }

    // 댓글 작성
    @PostMapping("/api/board/{boardId}/comment")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable Long boardId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        String content = request.get("content");
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "댓글 내용을 입력해주세요."));
        }

        if (content.trim().length() > 500) {
            return ResponseEntity.ok(Map.of("success", false, "message", "댓글은 500자 이하로 입력해주세요."));
        }

        return ResponseEntity.ok(boardService.addComment(boardId, member, content));
    }

    // 댓글 삭제
    @DeleteMapping("/api/board/comment/{commentId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteComment(
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        boolean isAdmin = member.getRole() == Member.MemberRole.ADMIN;
        return ResponseEntity.ok(boardService.deleteComment(commentId, member, isAdmin));
    }

    // 좋아요 토글
    @PostMapping("/api/board/{boardId}/like")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleLike(
            @PathVariable Long boardId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Member member = memberService.findLoginMember(userDetails);
        if (member == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "로그인이 필요합니다."));
        }

        return ResponseEntity.ok(boardService.toggleLike(boardId, member));
    }

    // 댓글 목록 조회 (AJAX용)
    @GetMapping("/api/board/{boardId}/comments")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getComments(@PathVariable Long boardId) {
        List<BoardComment> comments = boardService.getComments(boardId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (BoardComment comment : comments) {
            Map<String, Object> commentMap = new HashMap<>();
            commentMap.put("id", comment.getId());
            commentMap.put("content", comment.getContent());
            commentMap.put("nickname", comment.getMember().getNickname());
            commentMap.put("memberId", comment.getMember().getId());
            commentMap.put("createdAt", comment.getCreatedAt());
            result.add(commentMap);
        }

        return ResponseEntity.ok(result);
    }
}
