package com.kh.game.service;

import com.kh.game.dto.GameSettings;
import com.kh.game.exception.BusinessException;
import com.kh.game.entity.*;
import com.kh.game.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MultiGameService {

    private final GameRoomRepository gameRoomRepository;
    private final GameRoomParticipantRepository participantRepository;
    private final GameRoomChatRepository chatRepository;
    private final SongService songService;
    private final GenreService genreService;
    private final AnswerValidationService answerValidationService;
    private final MemberService memberService;
    private final MultiTierService multiTierService;
    private final BadgeService badgeService;
    private final ObjectMapper objectMapper;
    private final GameBroadcastService gameBroadcastService;

    // 이미 출제된 노래 ID를 방별로 관리 (스레드 안전)
    private final ConcurrentHashMap<Long, Set<Long>> usedSongsByRoom = new ConcurrentHashMap<>();

    // 방 단위 락 (메서드 레벨 synchronized 대체)
    private final ConcurrentHashMap<Long, Object> roomLocks = new ConcurrentHashMap<>();

    // ========== 게임 진행 ==========

    /**
     * 게임 시작 (방장만) - 대기 상태로 전환
     */
    @Transactional
    public void startGame(GameRoom room, Member host) {
        if (!room.isHost(host)) {
            throw new BusinessException("방장만 게임을 시작할 수 있습니다.");
        }

        if (room.getStatus() != GameRoom.RoomStatus.WAITING) {
            throw new BusinessException("이미 게임이 진행중입니다.");
        }

        List<GameRoomParticipant> participants = participantRepository.findActiveParticipants(room);
        if (participants.size() < 2) {
            throw new BusinessException("최소 2명 이상 필요합니다.");
        }

        boolean allReady = participants.stream().allMatch(GameRoomParticipant::getIsReady);
        if (!allReady) {
            throw new BusinessException("모든 참가자가 준비되지 않았습니다.");
        }

        // 게임 상태 변경
        room.setStatus(GameRoom.RoomStatus.PLAYING);
        room.setCurrentRound(0);
        room.setRoundPhase(null);  // 아직 라운드 시작 전

        // 참가자 상태 변경
        for (GameRoomParticipant p : participants) {
            p.setStatus(GameRoomParticipant.ParticipantStatus.PLAYING);
            p.resetScore();
        }

        // 사용된 노래 목록 초기화 (스레드 안전한 Set)
        usedSongsByRoom.put(room.getId(), ConcurrentHashMap.newKeySet());

        // 시스템 메시지
        addSystemMessage(room, host, "🎮 게임이 시작되었습니다! 방장이 라운드를 시작하면 노래가 재생됩니다.");
    }

    /**
     * 라운드 시작 (방장만) - 노래 선택 및 재생
     */
    @Transactional
    public Map<String, Object> startRound(GameRoom room, Member host) {
        Map<String, Object> result = new HashMap<>();

        if (!room.isHost(host)) {
            result.put("success", false);
            result.put("message", "방장만 라운드를 시작할 수 있습니다.");
            return result;
        }

        if (room.getStatus() != GameRoom.RoomStatus.PLAYING) {
            result.put("success", false);
            result.put("message", "게임이 진행중이 아닙니다.");
            return result;
        }

        // 이미 PLAYING 상태면 중복 시작 방지
        if (room.getRoundPhase() == GameRoom.RoundPhase.PLAYING) {
            result.put("success", false);
            result.put("message", "이미 라운드가 진행중입니다.");
            return result;
        }

        // 라운드 증가
        room.setCurrentRound(room.getCurrentRound() + 1);

        // 총 라운드 초과 체크
        if (room.getCurrentRound() > room.getTotalRounds()) {
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            return result;
        }

        // 노래 선택
        Song song = selectSong(room);
        if (song == null) {
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            result.put("message", "출제할 노래가 없습니다.");
            return result;
        }

        // 라운드 상태 설정 - 바로 PLAYING 단계로 시작
        room.setCurrentSong(song);
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setRoundStartTime(LocalDateTime.now());
        room.setWinner(null);  // 정답자 초기화

        // 스킵 투표 초기화
        resetSkipVotes(room);

        // 오디오 바로 재생
        room.setAudioPlaying(true);
        room.setAudioPlayedAt(System.currentTimeMillis());

        // 시스템 메시지
        addSystemMessage(room, host, "🎵 라운드 " + room.getCurrentRound() + " - 노래를 맞춰보세요!");

        result.put("success", true);
        result.put("isGameOver", false);
        result.put("currentRound", room.getCurrentRound());

        return result;
    }

    /**
     * 참가자 라운드 준비 완료
     */
    @Transactional
    public Map<String, Object> setRoundReady(GameRoom room, Member member) {
        Map<String, Object> result = new HashMap<>();

        if (room.getRoundPhase() != GameRoom.RoundPhase.PREPARING) {
            result.put("success", false);
            result.put("message", "준비 단계가 아닙니다.");
            return result;
        }

        GameRoomParticipant participant = findActiveParticipant(room, member)
                .orElse(null);
        if (participant == null) {
            result.put("success", false);
            result.put("message", "참가자가 아닙니다.");
            return result;
        }

        participant.setRoundReady(true);
        result.put("success", true);

        // 모든 참가자가 준비됐는지 체크
        boolean allReady = checkAllRoundReady(room);
        result.put("allReady", allReady);

        if (allReady) {
            // 자동으로 PLAYING 단계로 전환
            startPlaying(room);
        }

        return result;
    }

    /**
     * 모든 참가자가 라운드 준비 완료했는지 체크
     */
    private boolean checkAllRoundReady(GameRoom room) {
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        return participants.stream().allMatch(GameRoomParticipant::getRoundReady);
    }

    /**
     * PREPARING에서 PLAYING으로 전환 (모든 참가자 준비 완료 시)
     */
    private void startPlaying(GameRoom room) {
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setRoundStartTime(LocalDateTime.now());  // 실제 재생 시작 시간으로 리셋
        room.setAudioPlaying(true);
        room.setAudioPlayedAt(System.currentTimeMillis());

        addSystemMessage(room, room.getHost(), "🎵 모든 참가자 준비 완료! 노래를 맞춰보세요!");
    }

    /**
     * 현재 곡 스킵 (재생 오류 시 방장만)
     * PREPARING 또는 PLAYING 상태에서 호출 가능
     */
    @Transactional
    public Map<String, Object> skipCurrentSong(GameRoom room, Member host, Long songId) {
        synchronized (roomLocks.computeIfAbsent(room.getId(), k -> new Object())) {
        Map<String, Object> result = new HashMap<>();

        if (!room.isHost(host)) {
            result.put("success", false);
            result.put("message", "방장만 곡을 스킵할 수 있습니다.");
            return result;
        }

        // 종료된 방에서는 곡 교체·재종료 금지 (nextRound 와 같은 이유)
        if (room.getStatus() != GameRoom.RoomStatus.PLAYING) {
            result.put("success", false);
            result.put("message", "게임이 진행중이 아닙니다.");
            return result;
        }

        if (room.getRoundPhase() != GameRoom.RoundPhase.PLAYING) {
            result.put("success", false);
            result.put("message", "현재 스킵할 수 없는 상태입니다.");
            return result;
        }

        // 이미 정답자가 있으면 스킵 불가 (race condition 방지)
        if (room.getWinner() != null) {
            result.put("success", false);
            result.put("message", "이미 정답자가 있습니다.");
            return result;
        }

        // 현재 곡이 맞는지 확인
        Song currentSong = room.getCurrentSong();
        if (currentSong == null || !currentSong.getId().equals(songId)) {
            result.put("success", false);
            result.put("message", "이미 다른 곡이 선택되었습니다.");
            return result;
        }

        // 새 노래 선택
        Song newSong = selectSong(room);
        if (newSong == null) {
            // 더 이상 곡이 없으면 게임 종료
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            result.put("message", "출제할 노래가 없습니다.");
            return result;
        }

        // 노래 교체 및 바로 재생
        room.setCurrentSong(newSong);
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setRoundStartTime(LocalDateTime.now());
        room.setAudioPlaying(true);
        room.setAudioPlayedAt(System.currentTimeMillis());

        // 시스템 메시지
        addSystemMessage(room, host, "⚠️ 재생 오류로 다른 곡으로 변경되었습니다. 노래를 맞춰보세요!");

        result.put("success", true);
        result.put("isGameOver", false);
        result.put("currentRound", room.getCurrentRound());

        return result;
        } // synchronized
    }

    /**
     * 라운드 스킵 투표
     */
    @Transactional
    public Map<String, Object> voteSkipRound(GameRoom room, Member member) {
        Map<String, Object> result = new HashMap<>();

        if (room.getRoundPhase() != GameRoom.RoundPhase.PLAYING) {
            result.put("success", false);
            result.put("message", "현재 스킵 투표를 할 수 없는 상태입니다.");
            return result;
        }

        // 이미 정답자가 있으면 투표 불가
        if (room.getWinner() != null) {
            result.put("success", false);
            result.put("message", "이미 정답자가 있습니다.");
            return result;
        }

        GameRoomParticipant participant = findActiveParticipant(room, member)
                .orElse(null);
        if (participant == null) {
            result.put("success", false);
            result.put("message", "참가자가 아닙니다.");
            return result;
        }

        // 이미 스킵 투표했는지 확인
        if (Boolean.TRUE.equals(participant.getSkipVote())) {
            result.put("success", false);
            result.put("message", "이미 스킵 투표를 하셨습니다.");
            return result;
        }

        participant.setSkipVote(true);
        result.put("success", true);

        // 모든 참가자가 스킵 투표했는지 체크
        boolean allSkipped = checkAllSkipVotes(room);
        result.put("allSkipped", allSkipped);

        if (allSkipped) {
            // 라운드 스킵 처리
            handleRoundSkip(room);
            result.put("roundSkipped", true);
        }

        return result;
    }

    /**
     * 모든 참가자가 스킵 투표했는지 체크
     */
    private boolean checkAllSkipVotes(GameRoom room) {
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        return participants.stream().allMatch(p -> Boolean.TRUE.equals(p.getSkipVote()));
    }

    /**
     * 라운드 스킵 처리 (모든 참가자가 포기한 경우)
     */
    private void handleRoundSkip(GameRoom room) {
        // 오디오 정지
        room.setAudioPlaying(false);
        room.setAudioPlayedAt(null);

        // 라운드 결과로 전환 (정답자 없음)
        room.setRoundPhase(GameRoom.RoundPhase.RESULT);

        // 정답 정보 시스템 메시지
        Song song = room.getCurrentSong();
        if (song != null) {
            String skipMessage = String.format("⏭️ 모든 참가자가 포기했습니다. 정답: %s - %s", song.getArtist(), song.getTitle());
            addSystemMessage(room, room.getHost(), skipMessage);
        }
    }

    /**
     * 스킵 투표 현황 조회
     */
    public Map<String, Object> getSkipVoteStatus(GameRoom room) {
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        long votedCount = participants.stream().filter(p -> Boolean.TRUE.equals(p.getSkipVote())).count();

        Map<String, Object> status = new HashMap<>();
        status.put("votedCount", votedCount);
        status.put("totalCount", participants.size());
        status.put("allSkipped", votedCount == participants.size());
        return status;
    }

    /**
     * 참가자들의 스킵 투표 초기화
     */
    private void resetSkipVotes(GameRoom room) {
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        for (GameRoomParticipant p : participants) {
            p.setSkipVote(false);
        }
    }

    /**
     * 다음 라운드로 (방장만) - RESULT 상태에서 호출, 바로 다음 라운드 시작
     */
    @Transactional
    public Map<String, Object> nextRound(GameRoom room, Member host) {
        Map<String, Object> result = new HashMap<>();

        if (!room.isHost(host)) {
            result.put("success", false);
            result.put("message", "방장만 다음 라운드를 진행할 수 있습니다.");
            return result;
        }

        // 종료된 방에 다시 들어오면(탭 두 개·재시도) finishGame 이 재실행되어 전적·LP 가 이중 반영된다
        if (room.getStatus() != GameRoom.RoomStatus.PLAYING) {
            result.put("success", false);
            result.put("message", "게임이 진행중이 아닙니다.");
            return result;
        }

        // 마지막 라운드였으면 게임 종료
        if (room.getCurrentRound() >= room.getTotalRounds()) {
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            return result;
        }

        // 라운드 증가
        room.setCurrentRound(room.getCurrentRound() + 1);

        // 총 라운드 초과 체크
        if (room.getCurrentRound() > room.getTotalRounds()) {
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            return result;
        }

        // 노래 선택
        Song song = selectSong(room);
        if (song == null) {
            finishGame(room);
            result.put("success", true);
            result.put("isGameOver", true);
            result.put("message", "출제할 노래가 없습니다.");
            return result;
        }

        // 라운드 상태 설정 - 바로 PLAYING 단계로 시작
        room.setCurrentSong(song);
        room.setRoundPhase(GameRoom.RoundPhase.PLAYING);
        room.setRoundStartTime(LocalDateTime.now());
        room.setWinner(null);

        // 스킵 투표 초기화
        resetSkipVotes(room);

        // 오디오 바로 재생
        room.setAudioPlaying(true);
        room.setAudioPlayedAt(System.currentTimeMillis());

        // 시스템 메시지
        addSystemMessage(room, host, "🎵 라운드 " + room.getCurrentRound() + " - 노래를 맞춰보세요!");

        result.put("success", true);
        result.put("isGameOver", false);
        result.put("currentRound", room.getCurrentRound());

        return result;
    }

    // ========== 채팅 ==========

    /**
     * 채팅 전송 (정답 체크 포함)
     */
    @Transactional
    public Map<String, Object> sendChat(GameRoom room, Member member, String message) {
        Map<String, Object> result = new HashMap<>();

        if (message == null || message.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "메시지를 입력해주세요.");
            return result;
        }

        String trimmedMessage = message.trim();
        if (trimmedMessage.length() > 200) {
            trimmedMessage = trimmedMessage.substring(0, 200);
        }

        // 참가자 확인
        GameRoomParticipant participant = findActiveParticipant(room, member)
                .orElse(null);
        if (participant == null) {
            result.put("success", false);
            result.put("message", "참가자가 아닙니다.");
            return result;
        }

        // PLAYING 상태이고 정답자가 없으면 정답 체크
        boolean isCorrectAnswer = false;
        if (room.getRoundPhase() == GameRoom.RoundPhase.PLAYING && room.getWinner() == null) {
            Song currentSong = room.getCurrentSong();
            if (currentSong != null) {
                isCorrectAnswer = answerValidationService.validateAnswer(trimmedMessage, currentSong);
            }
        }

        GameRoomChat saved = null;
        if (isCorrectAnswer) {
            // 정답 처리 (이미 정답자가 있으면 null 반환)
            saved = handleCorrectAnswer(room, member, participant, trimmedMessage);
            if (saved != null) {
                result.put("isCorrect", true);
            } else {
                // 이미 다른 사람이 먼저 맞춤 - 일반 채팅으로 저장
                saved = chatRepository.save(GameRoomChat.chat(room, member, trimmedMessage));
                result.put("isCorrect", false);
            }
        } else {
            // 일반 채팅 저장
            saved = chatRepository.save(GameRoomChat.chat(room, member, trimmedMessage));
            result.put("isCorrect", false);
        }

        result.put("success", true);
        // CHAT push 페이로드 — 폴링 GET /chats 의 항목과 같은 형태 (id·memberId·isHost·messageType 포함)
        result.put("chat", toChatInfo(room, saved));
        return result;
    }

    /**
     * 정답 처리 (방 단위 락으로 동시 제출 방지)
     * @return 저장된 정답 채팅 (이미 정답자가 있으면 null)
     */
    private GameRoomChat handleCorrectAnswer(GameRoom room, Member member, GameRoomParticipant participant, String answer) {
        synchronized (roomLocks.computeIfAbsent(room.getId(), k -> new Object())) {
            // 이미 정답자가 있으면 무시 (동시 제출 방지)
            if (room.getWinner() != null) {
                return null;
            }

            // 정답자 설정
            room.setWinner(member);

            // 오디오 정지
            room.setAudioPlaying(false);
            room.setAudioPlayedAt(null);

            // 라운드 결과로 전환
            room.setRoundPhase(GameRoom.RoundPhase.RESULT);

            // 점수 추가 (100점 고정)
            participant.addScore(100);
            participant.incrementCorrect();

            // 정답 채팅 저장
            GameRoomChat correctChat = chatRepository.save(
                    GameRoomChat.correctAnswer(room, member, answer, room.getCurrentRound()));

            // 정답 정보 시스템 메시지 (song null 체크)
            Song song = room.getCurrentSong();
            if (song != null) {
                String answerMessage = String.format("🎉 정답: %s - %s", song.getArtist(), song.getTitle());
                addSystemMessage(room, member, answerMessage);
            }

            return correctChat;
        }
    }

    /**
     * 시스템 메시지 추가 — 저장 후 CHAT 으로 push 한다.
     * WebSocket 사용자는 채팅 폴링을 돌리지 않으므로 push 하지 않으면 시스템 메시지를 볼 수 없다.
     */
    private void addSystemMessage(GameRoom room, Member member, String message) {
        GameRoomChat systemChat = chatRepository.save(GameRoomChat.system(room, member, message));
        gameBroadcastService.broadcastChat(room.getRoomCode(), toChatInfo(room, systemChat));
    }

    /**
     * 채팅 목록 조회 (lastId 이후)
     */
    public List<Map<String, Object>> getChats(GameRoom room, Long lastId) {
        List<GameRoomChat> chats;
        if (lastId == null || lastId == 0) {
            chats = chatRepository.findByGameRoomOrderByCreatedAtAsc(room);
        } else {
            chats = chatRepository.findByGameRoomAndIdGreaterThan(room, lastId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (GameRoomChat chat : chats) {
            result.add(toChatInfo(room, chat));
        }

        return result;
    }

    /**
     * 채팅 한 건을 클라이언트 형태로 변환 (폴링 GET /chats 항목 · CHAT push 페이로드 공용)
     */
    private Map<String, Object> toChatInfo(GameRoom room, GameRoomChat chat) {
        Map<String, Object> chatInfo = new HashMap<>();
        chatInfo.put("id", chat.getId());
        chatInfo.put("memberId", chat.getMember().getId());
        chatInfo.put("nickname", chat.getMember().getNickname());
        chatInfo.put("message", chat.getMessage());
        chatInfo.put("messageType", chat.getMessageType().name());
        chatInfo.put("roundNumber", chat.getRoundNumber());
        // 저장 직후 push 시점에는 @CreationTimestamp 가 아직 없을 수 있다
        chatInfo.put("createdAt", chat.getCreatedAt() != null ? chat.getCreatedAt().toString() : LocalDateTime.now().toString());
        chatInfo.put("isHost", room.isHost(chat.getMember()));
        return chatInfo;
    }

    // ========== 게임 상태 조회 ==========

    /**
     * 현재 라운드 정보 조회
     */
    public Map<String, Object> getCurrentRoundInfo(GameRoom room) {
        Map<String, Object> info = new HashMap<>();

        info.put("currentRound", room.getCurrentRound());
        info.put("totalRounds", room.getTotalRounds());
        info.put("roundPhase", room.getRoundPhase() != null ? room.getRoundPhase().name() : null);
        info.put("status", room.getStatus().name());

        // 오디오 상태
        info.put("audioPlaying", room.getAudioPlaying());
        info.put("audioPlayedAt", room.getAudioPlayedAt());
        info.put("serverTime", System.currentTimeMillis());  // 클라이언트 시간 동기화용

        // 정답자 정보
        if (room.getWinner() != null) {
            info.put("winnerId", room.getWinner().getId());
            info.put("winnerNickname", room.getWinner().getNickname());
        }

        Song currentSong = room.getCurrentSong();
        // PLAYING 상태에서 노래 파일 정보 (정답은 숨김)
        if (currentSong != null && room.getRoundPhase() == GameRoom.RoundPhase.PLAYING) {
            Map<String, Object> songInfo = new HashMap<>();
            songInfo.put("id", currentSong.getId());
            // 빈 문자열이면 null로 변환 (YouTube Error 2 방지)
            String videoId = currentSong.getYoutubeVideoId();
            songInfo.put("youtubeVideoId", (videoId != null && !videoId.isBlank()) ? videoId : null);
            songInfo.put("filePath", currentSong.getFilePath());
            songInfo.put("startTime", currentSong.getStartTime());
            songInfo.put("playDuration", currentSong.getPlayDuration());
            info.put("song", songInfo);
        }

        // RESULT 상태에서 정답 정보
        if (room.getRoundPhase() == GameRoom.RoundPhase.RESULT && currentSong != null) {
            Map<String, Object> answerInfo = new HashMap<>();
            answerInfo.put("title", currentSong.getTitle());
            answerInfo.put("artist", currentSong.getArtist());
            answerInfo.put("releaseYear", currentSong.getReleaseYear());
            if (currentSong.getGenre() != null) {
                answerInfo.put("genre", currentSong.getGenre().getName());
            }
            info.put("answer", answerInfo);
        }

        // 참가자별 점수 (PLAYING 상태도 포함)
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        List<Map<String, Object>> participantInfos = new ArrayList<>();
        int skipVoteCount = 0;
        for (GameRoomParticipant p : participants) {
            Map<String, Object> pInfo = new HashMap<>();
            pInfo.put("memberId", p.getMember().getId());
            pInfo.put("nickname", p.getMember().getNickname());
            pInfo.put("score", p.getScore());
            pInfo.put("correctCount", p.getCorrectCount());
            pInfo.put("isHost", room.isHost(p.getMember()));
            pInfo.put("roundReady", p.getRoundReady());  // 라운드 준비 상태 추가
            pInfo.put("skipVote", p.getSkipVote());  // 스킵 투표 상태 추가
            participantInfos.add(pInfo);
            if (Boolean.TRUE.equals(p.getSkipVote())) {
                skipVoteCount++;
            }
        }

        // 스킵 투표 현황
        Map<String, Object> skipVoteStatus = new HashMap<>();
        skipVoteStatus.put("votedCount", skipVoteCount);
        skipVoteStatus.put("totalCount", participants.size());
        info.put("skipVoteStatus", skipVoteStatus);

        // 점수순 정렬
        participantInfos.sort((a, b) -> (Integer) b.get("score") - (Integer) a.get("score"));
        info.put("participants", participantInfos);

        return info;
    }

    /**
     * 최종 결과 조회 (LP 정보 포함)
     * 모든 참가자를 표시 (LEFT 상태도 포함 - 결과 화면에서 탈퇴한 경우에도 표시)
     */
    public List<Map<String, Object>> getFinalResult(GameRoom room) {
        // 모든 참가자 조회 (status 필터 없이)
        List<GameRoomParticipant> allParticipants = participantRepository.findAllByGameRoomOrderByScoreDesc(room);

        // 게임에 참여한 참가자 필터링:
        // - PLAYING 상태 (게임 중이었음)
        // - JOINED 상태 (재시작 대기 중)
        // - 또는 점수/정답이 있는 경우 (확실히 참여함)
        // - LEFT 상태이고 점수도 없으면 제외 (게임 시작 전에 나간 사람)
        List<GameRoomParticipant> participants = allParticipants.stream()
                .filter(p -> p.getStatus() == GameRoomParticipant.ParticipantStatus.PLAYING
                        || p.getStatus() == GameRoomParticipant.ParticipantStatus.JOINED
                        || p.getScore() > 0
                        || p.getCorrectCount() > 0)
                .toList();

        int totalPlayers = participants.size();
        List<Map<String, Object>> result = new ArrayList<>();

        // 게임에서 누군가 점수를 얻었는지 확인 (전원 0점이면 LP 미지급)
        int totalScore = participants.stream().mapToInt(GameRoomParticipant::getScore).sum();
        boolean hasValidGame = totalScore > 0;

        // 전원 동점 여부 확인 (2명 이상이고 모든 점수가 같으면 전원 동점)
        boolean allTied = participants.size() > 1 &&
                participants.stream().mapToInt(GameRoomParticipant::getScore).distinct().count() == 1;

        // 동점자 공동 순위 계산
        int prevScore = -1;
        int displayRank = 1;

        for (int i = 0; i < participants.size(); i++) {
            GameRoomParticipant p = participants.get(i);
            Member member = p.getMember();

            // 공동 순위 계산: 점수가 같으면 같은 순위, 다르면 현재 위치+1
            if (i == 0) {
                displayRank = 1;
            } else if (p.getScore() != prevScore) {
                displayRank = i + 1;
            }
            // 동점이면 displayRank 유지
            prevScore = p.getScore();

            Map<String, Object> pInfo = new HashMap<>();
            pInfo.put("rank", displayRank);
            pInfo.put("memberId", member.getId());
            pInfo.put("nickname", member.getNickname());
            pInfo.put("score", p.getScore());
            pInfo.put("correctCount", p.getCorrectCount());
            pInfo.put("isHost", room.isHost(member));
            pInfo.put("allTied", allTied);  // 각 결과에 전원 동점 여부 추가

            // 멀티 티어 정보 추가
            pInfo.put("multiTier", member.getMultiTier() != null ? member.getMultiTier().name() : "BRONZE");
            pInfo.put("multiTierDisplayName", member.getMultiTierDisplayName());
            pInfo.put("multiTierColor", member.getMultiTierColor());
            pInfo.put("multiLp", member.getMultiLp() != null ? member.getMultiLp() : 0);

            // LP 변화량 계산 (표시용) - 전원 0점이면 LP 미지급, 공동 순위 기준
            int lpChange = hasValidGame ? multiTierService.calculateLpChange(totalPlayers, displayRank) : 0;
            pInfo.put("lpChange", lpChange);

            result.add(pInfo);
        }

        return result;
    }

    // ========== 내부 헬퍼 ==========

    /**
     * 게임 액션(채팅·포기 투표·라운드 준비)용 참가자 조회 — 활성(JOINED/PLAYING)만.
     * 나갔거나 강퇴된(LEFT) 사람의 행은 남아 있으므로 상태를 보지 않으면 정답을 맞혀 점수를 얻을 수 있다.
     * 구독·폴링 GET 인가(GameRoomService.isActiveParticipant)와 같은 기준.
     */
    private Optional<GameRoomParticipant> findActiveParticipant(GameRoom room, Member member) {
        return participantRepository.findByGameRoomAndMember(room, member)
                .filter(p -> p.getStatus() != GameRoomParticipant.ParticipantStatus.LEFT);
    }

    /**
     * 노래 선택 + 사용 기록을 원자적으로 수행 (중복 선택 방지)
     */
    private Song selectSong(GameRoom room) {
        Set<Long> usedSongs = usedSongsByRoom.getOrDefault(room.getId(), Collections.emptySet());

        // GameSettings 파싱
        GameSettings settings = parseGameSettings(room);

        // YouTube 검증 포함된 메서드 사용
        Song song = songService.getValidatedRandomSongWithSettings(settings, usedSongs);

        // 선택된 곡을 즉시 사용 기록에 추가 (원자적)
        if (song != null) {
            usedSongsByRoom.computeIfAbsent(room.getId(), k -> ConcurrentHashMap.newKeySet()).add(song.getId());
        }

        return song;
    }

    /**
     * 방 설정에서 GameSettings 파싱
     */
    @SuppressWarnings("unchecked")
    private GameSettings parseGameSettings(GameRoom room) {
        GameSettings settings = new GameSettings();

        try {
            if (room.getSettings() != null) {
                Map<String, Object> settingsMap = objectMapper.readValue(room.getSettings(), Map.class);

                // 게임 모드
                String gameMode = (String) settingsMap.getOrDefault("gameMode", "RANDOM");
                settings.setGameMode(gameMode);

                // 장르 필터
                if ("FIXED_GENRE".equals(gameMode)) {
                    Object genreId = settingsMap.get("fixedGenreId");
                    if (genreId != null) {
                        settings.setFixedGenreId(((Number) genreId).longValue());
                    }
                }

                // 아티스트 필터 (복수 선택)
                if ("FIXED_ARTIST".equals(gameMode)) {
                    Object artists = settingsMap.get("selectedArtists");
                    if (artists instanceof List) {
                        settings.setSelectedArtists((List<String>) artists);
                    }
                }

                // 연도 필터 (복수 선택)
                if ("FIXED_YEAR".equals(gameMode)) {
                    Object years = settingsMap.get("selectedYears");
                    if (years instanceof List) {
                        settings.setSelectedYears((List<Integer>) years);
                    }
                }

                // 솔로/그룹 필터
                Object soloOnly = settingsMap.get("soloOnly");
                if (soloOnly instanceof Boolean) {
                    settings.setSoloOnly((Boolean) soloOnly);
                }

                Object groupOnly = settingsMap.get("groupOnly");
                if (groupOnly instanceof Boolean) {
                    settings.setGroupOnly((Boolean) groupOnly);
                }
            }
        } catch (Exception e) {
            log.error("설정 파싱 오류", e);
        }

        return settings;
    }

    /**
     * 방 종료 시 정리
     */
    @Transactional
    public void cleanupRoom(GameRoom room) {
        usedSongsByRoom.remove(room.getId());
        roomLocks.remove(room.getId());
        room.setStatus(GameRoom.RoomStatus.FINISHED);
    }

    /**
     * 게임 종료 처리 - Member 통계 업데이트 및 LP 적용 (ELO 기반)
     * 전원 0점인 경우 LP를 적용하지 않음 (실제 게임이 진행되지 않은 것으로 간주)
     */
    @Transactional
    public List<MultiTierService.LpChangeResult> finishGame(GameRoom room) {
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        usedSongsByRoom.remove(room.getId());
        roomLocks.remove(room.getId());

        // 모든 참가자의 통계를 Member에 반영
        List<GameRoomParticipant> participants = participantRepository.findGameParticipants(room);
        int totalRounds = room.getCurrentRound();  // 실제 진행된 라운드 수
        int totalPlayers = participants.size();

        // 전원 0점 여부 확인 (게임이 실제로 진행되었는지)
        int totalScore = participants.stream().mapToInt(GameRoomParticipant::getScore).sum();
        boolean hasValidGame = totalScore > 0;

        // 참가자들의 현재 레이팅 수집 (LP 적용 전 스냅샷)
        Map<Long, Integer> participantRatings = new HashMap<>();
        for (GameRoomParticipant participant : participants) {
            Member member = participant.getMember();
            if (member != null) {
                MultiTier tier = member.getMultiTier() != null ? member.getMultiTier() : MultiTier.BRONZE;
                int lp = member.getMultiLp() != null ? member.getMultiLp() : 0;
                participantRatings.put(member.getId(), tier.toRating(lp));
            }
        }

        // 점수순 정렬 (순위 계산용)
        List<GameRoomParticipant> rankedParticipants = participants.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .toList();

        List<MultiTierService.LpChangeResult> lpResults = new ArrayList<>();

        for (int i = 0; i < rankedParticipants.size(); i++) {
            GameRoomParticipant participant = rankedParticipants.get(i);
            Member member = participant.getMember();
            int rank = i + 1;

            if (member != null) {
                // 기존 통계 업데이트 (게임 진행 여부와 관계없이 기록)
                memberService.addMultiGameResult(
                        member.getId(),
                        participant.getScore(),
                        participant.getCorrectCount(),
                        totalRounds
                );

                // LP 적용 및 티어 변동 처리 - 전원 0점이면 LP 미적용
                if (hasValidGame) {
                    MultiTierService.LpChangeResult lpResult = multiTierService.applyGameResult(
                            member.getId(),
                            totalPlayers,
                            rank,
                            participant.getScore(),
                            participantRatings
                    );
                    lpResults.add(lpResult);

                    // 뱃지 체크 및 획득 (통계 업데이트 후)
                    Member updatedMember = memberService.findById(member.getId()).orElse(null);
                    if (updatedMember != null) {
                        List<Badge> newBadges = badgeService.checkBadgesAfterMultiGame(
                                updatedMember,
                                rank,
                                totalPlayers
                        );
                        if (!newBadges.isEmpty()) {
                            lpResult.setNewBadges(newBadges);
                        }
                    }
                }
            }
        }

        log.info("멀티게임 종료 - 방: {}, 참가자: {}명, 라운드: {}, LP적용: {}",
                room.getId(), totalPlayers, totalRounds, hasValidGame);

        return lpResults;
    }
}