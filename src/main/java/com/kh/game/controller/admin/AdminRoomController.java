package com.kh.game.controller.admin;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomChat;
import com.kh.game.repository.GameRoomChatRepository;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.service.GameRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequestMapping("/admin/room")
@RequiredArgsConstructor
public class AdminRoomController {

    private final GameRoomRepository gameRoomRepository;
    private final GameRoomChatRepository gameRoomChatRepository;
    private final GameRoomParticipantRepository participantRepository;
    private final GameRoomService gameRoomService;

    /**
     * 레거시 URL → 통합 페이지로 리다이렉트
     * /admin/room → /admin/game?tab=multi (멀티 운영 탭, 기본 서브탭이 room)
     */
    @GetMapping
    public String redirectToMulti() {
        return "redirect:/admin/game?tab=multi";
    }

    /**
     * AJAX 로딩용 방 목록 콘텐츠 (fragment)
     */
    @GetMapping("/content")
    public String listContent(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              Model model) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<GameRoom> roomPage;

        // 검색 조건 파싱
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        GameRoom.RoomStatus roomStatus = null;
        if (status != null && !status.isEmpty()) {
            try {
                roomStatus = GameRoom.RoomStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                // 잘못된 상태값 무시
            }
        }

        // 검색 조건에 따라 조회
        if (hasKeyword && roomStatus != null) {
            // 키워드 + 상태 동시 검색
            roomPage = gameRoomRepository.searchByKeywordAndStatus(keyword, roomStatus, pageable);
        } else if (hasKeyword) {
            // 키워드만 검색
            roomPage = gameRoomRepository.searchByKeyword(keyword, pageable);
        } else if (roomStatus != null) {
            // 상태만 필터
            roomPage = gameRoomRepository.findByStatusOrderByCreatedAtDesc(roomStatus, pageable);
        } else {
            // 전체 조회
            roomPage = gameRoomRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        // 항상 검색 조건을 model에 추가 (폼 상태 유지)
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);

        // 통계 정보
        long totalRooms = gameRoomRepository.count();
        long waitingCount = gameRoomRepository.countByStatus(GameRoom.RoomStatus.WAITING);
        long playingCount = gameRoomRepository.countByStatus(GameRoom.RoomStatus.PLAYING);
        long finishedCount = gameRoomRepository.countByStatus(GameRoom.RoomStatus.FINISHED);

        model.addAttribute("rooms", roomPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("size", size);
        model.addAttribute("totalPages", roomPage.getTotalPages());
        model.addAttribute("totalItems", roomPage.getTotalElements());
        model.addAttribute("totalRooms", totalRooms);
        model.addAttribute("waitingCount", waitingCount);
        model.addAttribute("playingCount", playingCount);
        model.addAttribute("finishedCount", finishedCount);

        return "admin/multi/fragments/room";
    }

    /**
     * 방 상세 정보 (JSON)
     */
    @GetMapping("/detail/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        return gameRoomRepository.findById(id)
                .map(room -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", room.getId());
                    result.put("roomCode", room.getRoomCode());
                    result.put("roomName", room.getRoomName());
                    result.put("hostNickname", room.getHost() != null ? room.getHost().getNickname() : "-");
                    result.put("hostId", room.getHost() != null ? room.getHost().getId() : null);
                    result.put("status", room.getStatus().name());
                    result.put("maxPlayers", room.getMaxPlayers());
                    result.put("currentPlayers", room.getCurrentPlayerCount());
                    result.put("totalRounds", room.getTotalRounds());
                    result.put("currentRound", room.getCurrentRound());
                    result.put("isPrivate", room.getIsPrivate());
                    result.put("settings", room.getSettings());
                    result.put("createdAt", room.getCreatedAt());
                    result.put("updatedAt", room.getUpdatedAt());

                    // 참가자 목록
                    var participants = participantRepository.findActiveParticipants(room)
                            .stream()
                            .map(p -> {
                                Map<String, Object> pMap = new HashMap<>();
                                pMap.put("id", p.getId());
                                pMap.put("nickname", p.getMember().getNickname());
                                pMap.put("score", p.getScore());
                                pMap.put("isReady", p.getIsReady());
                                pMap.put("status", p.getStatus().name());
                                return pMap;
                            })
                            .collect(Collectors.toList());
                    result.put("participants", participants);

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 방 강제 종료
     */
    @PostMapping("/close/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> closeRoom(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            GameRoom room = gameRoomRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("방을 찾을 수 없습니다."));

            room.setStatus(GameRoom.RoomStatus.FINISHED);
            gameRoomRepository.save(room);

            result.put("success", true);
            result.put("message", "방이 종료되었습니다.");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("방 종료 실패: roomId={}", id, e);
            result.put("success", false);
            result.put("message", "방 종료 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 방 삭제 (채팅 포함)
     */
    @PostMapping("/delete/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteRoom(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            GameRoom room = gameRoomRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("방을 찾을 수 없습니다."));

            // 채팅 삭제
            gameRoomChatRepository.deleteByGameRoom(room);

            // 방 삭제
            gameRoomRepository.delete(room);

            result.put("success", true);
            result.put("message", "방이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("방 삭제 실패: roomId={}", id, e);
            result.put("success", false);
            result.put("message", "방 삭제 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 방 채팅 내역 조회
     */
    @GetMapping("/chat/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getChatHistory(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            GameRoom room = gameRoomRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("방을 찾을 수 없습니다."));

            List<GameRoomChat> chats = gameRoomChatRepository.findByGameRoomOrderByCreatedAtAsc(room);

            List<Map<String, Object>> chatList = chats.stream()
                    .map(chat -> {
                        Map<String, Object> chatMap = new HashMap<>();
                        chatMap.put("id", chat.getId());
                        chatMap.put("nickname", chat.getMember() != null ? chat.getMember().getNickname() : "알수없음");
                        chatMap.put("message", chat.getMessage());
                        chatMap.put("messageType", chat.getMessageType().name());
                        chatMap.put("roundNumber", chat.getRoundNumber());
                        chatMap.put("createdAt", chat.getCreatedAt());
                        return chatMap;
                    })
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("roomName", room.getRoomName());
            result.put("roomCode", room.getRoomCode());
            result.put("chats", chatList);
            result.put("totalCount", chatList.size());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("채팅 내역 조회 실패: roomId={}", id, e);
            result.put("success", false);
            result.put("message", "채팅 내역 조회 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 채팅 삭제
     */
    @PostMapping("/chat/delete/{chatId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteChat(@PathVariable Long chatId) {
        Map<String, Object> result = new HashMap<>();
        try {
            gameRoomChatRepository.deleteById(chatId);
            result.put("success", true);
            result.put("message", "채팅이 삭제되었습니다.");
        } catch (Exception e) {
            log.error("채팅 삭제 실패: chatId={}", chatId, e);
            result.put("success", false);
            result.put("message", "채팅 삭제 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 방의 모든 채팅 삭제
     */
    @PostMapping("/chat/delete-all/{roomId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteAllChats(@PathVariable Long roomId) {
        Map<String, Object> result = new HashMap<>();
        try {
            GameRoom room = gameRoomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("방을 찾을 수 없습니다."));

            gameRoomChatRepository.deleteByGameRoom(room);

            result.put("success", true);
            result.put("message", "모든 채팅이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("방 전체 채팅 삭제 실패: roomId={}", roomId, e);
            result.put("success", false);
            result.put("message", "채팅 삭제 중 오류가 발생했습니다.");
        }
        return ResponseEntity.ok(result);
    }
}