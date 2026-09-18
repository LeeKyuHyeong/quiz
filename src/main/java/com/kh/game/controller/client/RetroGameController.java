package com.kh.game.controller.client;

import com.kh.game.dto.GameSettings;
import com.kh.game.entity.*;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.BadgeService;
import com.kh.game.service.GameSessionService;
import com.kh.game.service.MemberService;
import com.kh.game.service.SongService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 레트로 게임 컨트롤러
 * 2000년 이전 노래 + RETRO 장르 곡을 대상으로 한 게임
 */
@Controller
@RequestMapping("/game/retro")
@RequiredArgsConstructor
public class RetroGameController {

    private final SongService songService;
    private final GameSessionService gameSessionService;
    private final MemberService memberService;
    private final BadgeService badgeService;

    @GetMapping
    public String setup(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        Member member = memberService.findLoginMember(userDetails);
        model.addAttribute("nickname", member != null ? member.getNickname() : null);
        long retroSongCount = songService.countRetroSongs();
        model.addAttribute("retroSongCount", retroSongCount);
        return "client/game/retro/setup";
    }

    @PostMapping("/song-count")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSongCount(
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new HashMap<>();

        GameSettings settings = new GameSettings();

        if (request.get("soloOnly") != null) {
            settings.setSoloOnly((Boolean) request.get("soloOnly"));
        }
        if (request.get("groupOnly") != null) {
            settings.setGroupOnly((Boolean) request.get("groupOnly"));
        }

        int count = songService.getAvailableRetroSongCount(settings);
        result.put("count", count);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startGame(
            @RequestBody Map<String, Object> request,
            HttpSession httpSession,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Map<String, Object> result = new HashMap<>();

        try {
            String nickname = (String) request.get("nickname");
            int totalRounds = (int) request.get("totalRounds");

            @SuppressWarnings("unchecked")
            Map<String, Object> settingsMap = (Map<String, Object>) request.get("settings");

            // GameSession 생성
            GameSession session = new GameSession();
            session.setSessionUuid(UUID.randomUUID().toString());
            session.setNickname(nickname);
            session.setGameType(GameSession.GameType.RETRO_GUESS);
            session.setGameMode(GameSession.GameMode.RANDOM);  // 레트로는 전체 랜덤만 지원
            session.setTotalRounds(totalRounds);
            session.setStatus(GameSession.GameStatus.PLAYING);

            // 로그인한 회원인 경우 연결
            Long memberId = userDetails != null ? userDetails.getMemberId() : null;
            if (memberId != null) {
                memberService.findById(memberId).ifPresent(session::setMember);
            }

            // Settings JSON 생성
            GameSettings settings = new GameSettings();
            if (settingsMap != null) {
                if (settingsMap.get("timeLimit") != null) {
                    settings.setTimeLimit((Integer) settingsMap.get("timeLimit"));
                }
                if (settingsMap.get("soloOnly") != null) {
                    settings.setSoloOnly((Boolean) settingsMap.get("soloOnly"));
                }
                if (settingsMap.get("groupOnly") != null) {
                    settings.setGroupOnly((Boolean) settingsMap.get("groupOnly"));
                }
            }
            session.setSettings(gameSessionService.toSettingsJson(settings));

            // 레트로 곡 목록 가져오기 (YouTube 검증 포함)
            SongService.ValidatedSongsResult validatedResult =
                    songService.getRandomRetroSongsWithValidation(totalRounds, settings);
            List<Song> songs = validatedResult.getSongs();

            if (songs.size() < totalRounds) {
                // 곡이 부족한 경우
                if (songs.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "레트로 곡이 부족합니다. 최소 1곡 이상 필요합니다.");
                    return ResponseEntity.ok(result);
                }
            }

            // 실제 생성된 라운드 수로 업데이트
            int actualRounds = songs.size();
            session.setTotalRounds(actualRounds);

            GameSession savedSession = gameSessionService.save(session);

            for (int i = 0; i < songs.size(); i++) {
                GameRound round = new GameRound();
                round.setGameSession(savedSession);
                round.setRoundNumber(i + 1);
                round.setSong(songs.get(i));
                round.setGenre(songs.get(i).getGenre());
                round.setPlayStartTime(songs.get(i).getStartTime());
                round.setPlayDuration(songs.get(i).getPlayDuration());
                round.setStatus(GameRound.RoundStatus.WAITING);
                savedSession.getRounds().add(round);
            }

            gameSessionService.save(savedSession);

            // 세션에 정보 저장
            httpSession.setAttribute("retroSessionId", savedSession.getId());
            httpSession.setAttribute("retroNickname", nickname);
            httpSession.setAttribute("retroPlayedSongIds", new ArrayList<Long>());
            httpSession.setAttribute("retroScore", 0);

            result.put("success", true);
            result.put("sessionId", savedSession.getId());
            result.put("requestedRounds", totalRounds);
            result.put("actualRounds", actualRounds);
            result.put("reducedCount", totalRounds - actualRounds);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "게임 시작 중 오류가 발생했습니다: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/play")
    public String play(HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("retroSessionId");
        if (sessionId == null) {
            return "redirect:/game/retro";
        }

        GameSession session = gameSessionService.findById(sessionId).orElse(null);
        if (session == null) {
            return "redirect:/game/retro";
        }

        String nickname = (String) httpSession.getAttribute("retroNickname");

        model.addAttribute("gameSession", session);
        model.addAttribute("nickname", nickname);
        model.addAttribute("settings", gameSessionService.parseSettings(session.getSettings()));

        return "client/game/retro/play";
    }

    @GetMapping("/round/{roundNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRound(
            @PathVariable int roundNumber,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();
        Long sessionId = (Long) httpSession.getAttribute("retroSessionId");

        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 없습니다.");
            return ResponseEntity.ok(result);
        }

        GameSession session = gameSessionService.findById(sessionId).orElse(null);
        if (session == null) {
            result.put("success", false);
            result.put("message", "게임을 찾을 수 없습니다.");
            return ResponseEntity.ok(result);
        }

        GameRound round = session.getRounds().stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .orElse(null);

        if (round == null) {
            result.put("success", false);
            result.put("message", "라운드를 찾을 수 없습니다.");
            return ResponseEntity.ok(result);
        }

        result.put("success", true);
        result.put("roundNumber", round.getRoundNumber());
        result.put("totalRounds", session.getTotalRounds());

        if (round.getSong() != null) {
            Map<String, Object> songInfo = new HashMap<>();
            songInfo.put("id", round.getSong().getId());
            songInfo.put("filePath", round.getSong().getFilePath());
            songInfo.put("youtubeVideoId", round.getSong().getYoutubeVideoId());
            songInfo.put("startTime", round.getSong().getStartTime());
            songInfo.put("playDuration", round.getPlayDuration());
            songInfo.put("releaseYear", round.getSong().getReleaseYear());
            if (round.getSong().getGenre() != null) {
                songInfo.put("genre", round.getSong().getGenre().getName());
            }
            result.put("song", songInfo);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/answer")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitAnswer(
            @RequestBody Map<String, Object> request,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();
        Long sessionId = (Long) httpSession.getAttribute("retroSessionId");

        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 없습니다.");
            return ResponseEntity.ok(result);
        }

        try {
            int roundNumber = (int) request.get("roundNumber");
            String userAnswer = (String) request.get("answer");
            boolean isSkip = request.get("isSkip") != null && (boolean) request.get("isSkip");

            GameSession session = gameSessionService.findById(sessionId).orElse(null);
            if (session == null) {
                result.put("success", false);
                result.put("message", "게임을 찾을 수 없습니다.");
                return ResponseEntity.ok(result);
            }

            GameRound round = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == roundNumber)
                    .findFirst()
                    .orElse(null);

            if (round == null) {
                result.put("success", false);
                result.put("message", "라운드를 찾을 수 없습니다.");
                return ResponseEntity.ok(result);
            }

            Song song = round.getSong();

            // 시간 기반 점수 계산 (초 단위)
            Double answerTimeSec = request.get("answerTime") != null ?
                    Double.parseDouble(request.get("answerTime").toString()) : null;
            int maxAttempts = 3;

            if (isSkip) {
                // 스킵 처리
                round.setStatus(GameRound.RoundStatus.SKIPPED);
                round.setIsCorrect(false);
                session.setSkipCount(session.getSkipCount() + 1);
                session.setCompletedRounds(session.getCompletedRounds() + 1);

                result.put("isCorrect", false);
                result.put("isRoundOver", true);
                result.put("remainingAttempts", 0);

            } else {
                // 시도 횟수 증가
                int currentAttempt = (round.getAttemptCount() == null ? 0 : round.getAttemptCount()) + 1;
                round.setAttemptCount(currentAttempt);

                // 정답 체크
                boolean isCorrect = songService.checkAnswer(song.getId(), userAnswer);

                // 시도 기록 저장
                GameRoundAttempt attempt = new GameRoundAttempt(round, currentAttempt, userAnswer, isCorrect);
                round.getAttempts().add(attempt);

                if (isCorrect) {
                    // 정답!
                    round.setStatus(GameRound.RoundStatus.ANSWERED);
                    round.setUserAnswer(userAnswer);
                    round.setIsCorrect(true);

                    // 시간 기반 점수 계산
                    int earnedScore = calculateScoreByTime(answerTimeSec);
                    round.setScore(earnedScore);
                    if (answerTimeSec != null) {
                        round.setAnswerTimeMs((long) (answerTimeSec * 1000));
                    }
                    session.setCorrectCount(session.getCorrectCount() + 1);
                    session.setTotalScore(session.getTotalScore() + earnedScore);
                    session.setCompletedRounds(session.getCompletedRounds() + 1);

                    result.put("isCorrect", true);
                    result.put("isRoundOver", true);
                    result.put("earnedScore", earnedScore);
                    result.put("answerTime", answerTimeSec);
                    result.put("attemptCount", currentAttempt);

                } else if (currentAttempt >= maxAttempts) {
                    // 3번 모두 실패
                    round.setStatus(GameRound.RoundStatus.ANSWERED);
                    round.setUserAnswer(userAnswer);
                    round.setIsCorrect(false);
                    round.setScore(0);
                    session.setCompletedRounds(session.getCompletedRounds() + 1);

                    result.put("isCorrect", false);
                    result.put("isRoundOver", true);
                    result.put("remainingAttempts", 0);
                    result.put("attemptCount", currentAttempt);

                } else {
                    // 아직 기회 남음
                    result.put("isCorrect", false);
                    result.put("isRoundOver", false);
                    result.put("remainingAttempts", maxAttempts - currentAttempt);
                    result.put("attemptCount", currentAttempt);
                }
            }

            // 게임 종료 체크
            if (session.getCompletedRounds() >= session.getTotalRounds()) {
                session.setStatus(GameSession.GameStatus.COMPLETED);
                session.setEndedAt(LocalDateTime.now());

                // 회원인 경우 레트로 통계 업데이트
                if (session.getMember() != null) {
                    // 최고기록 랭킹 대상 여부 확인
                    // 레트로는 필터 없이 전체 랜덤만 지원하므로 항상 true
                    GameSettings settings = gameSessionService.parseSettings(session.getSettings());
                    boolean allArtistTypes = !Boolean.TRUE.equals(settings.getSoloOnly())
                                           && !Boolean.TRUE.equals(settings.getGroupOnly());
                    boolean isEligibleForBestScore = allArtistTypes;

                    memberService.addRetroGameResult(
                            session.getMember().getId(),
                            session.getTotalScore(),
                            session.getCorrectCount(),
                            session.getCompletedRounds(),
                            session.getSkipCount(),
                            isEligibleForBestScore
                    );

                    // 30곡 최고점 랭킹 갱신 (30곡 게임만 대상)
                    if (session.getTotalRounds() == Member.RANKING_ROUNDS && isEligibleForBestScore) {
                        boolean updated = memberService.updateRetro30SongBestScore(
                                session.getMember().getId(),
                                session.getTotalScore()
                        );
                        if (updated) {
                            result.put("retroBest30Updated", true);
                        }
                    }

                    // 뱃지 체크 및 획득
                    Member member = memberService.findById(session.getMember().getId()).orElse(null);
                    if (member != null) {
                        List<Badge> newBadges = badgeService.checkBadgesAfterGuessGame(
                                member,
                                session.getTotalScore(),
                                session.getCorrectCount(),
                                session.getCompletedRounds()
                        );
                        if (!newBadges.isEmpty()) {
                            result.put("newBadges", newBadges.stream()
                                    .map(b -> Map.of(
                                            "name", b.getName(),
                                            "emoji", b.getEmoji(),
                                            "rarity", b.getRarity().name(),
                                            "rarityColor", b.getRarity().getColor()
                                    ))
                                    .toList());
                        }
                    }
                }
            }

            gameSessionService.save(session);

            result.put("success", true);
            result.put("isGameOver", session.getStatus() == GameSession.GameStatus.COMPLETED);
            result.put("completedRounds", session.getCompletedRounds());
            result.put("totalScore", session.getTotalScore());

            // 라운드 종료 시 정답 정보 반환
            boolean isRoundOver = (boolean) result.get("isRoundOver");
            if (isRoundOver) {
                Map<String, Object> answerInfo = new HashMap<>();
                answerInfo.put("title", song.getTitle());
                answerInfo.put("artist", song.getArtist());
                answerInfo.put("releaseYear", song.getReleaseYear());
                if (song.getGenre() != null) {
                    answerInfo.put("genre", song.getGenre().getName());
                }
                result.put("answer", answerInfo);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "처리 중 오류가 발생했습니다: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/result")
    public String result(HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("retroSessionId");
        if (sessionId == null) {
            return "redirect:/game/retro";
        }

        GameSession session = gameSessionService.findById(sessionId).orElse(null);
        if (session == null) {
            return "redirect:/game/retro";
        }

        String nickname = (String) httpSession.getAttribute("retroNickname");

        // attempts까지 함께 로드된 rounds 조회
        List<GameRound> roundsWithAttempts = gameSessionService.findRoundsWithAttemptsBySessionId(sessionId);

        model.addAttribute("gameSession", session);
        model.addAttribute("rounds", roundsWithAttempts);
        model.addAttribute("nickname", nickname);

        return "client/game/retro/result";
    }

    @PostMapping("/end")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> endGame(HttpSession httpSession) {
        Map<String, Object> result = new HashMap<>();
        Long sessionId = (Long) httpSession.getAttribute("retroSessionId");

        if (sessionId != null) {
            GameSession session = gameSessionService.findById(sessionId).orElse(null);
            if (session != null && session.getStatus() == GameSession.GameStatus.PLAYING) {
                session.setStatus(GameSession.GameStatus.ABANDONED);
                session.setEndedAt(LocalDateTime.now());
                gameSessionService.save(session);
            }
        }

        httpSession.removeAttribute("retroSessionId");
        httpSession.removeAttribute("retroNickname");
        httpSession.removeAttribute("retroPlayedSongIds");
        httpSession.removeAttribute("retroScore");

        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    /**
     * 같은 설정으로 다시하기
     */
    @PostMapping("/restart")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> restartGame(
            @RequestBody Map<String, Object> request,
            HttpSession httpSession,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Map<String, Object> result = new HashMap<>();

        try {
            Long previousSessionId = Long.valueOf(request.get("previousSessionId").toString());

            // 이전 세션 조회
            GameSession previous = gameSessionService.findById(previousSessionId).orElse(null);
            if (previous == null) {
                result.put("success", false);
                result.put("message", "이전 게임 정보를 찾을 수 없습니다.");
                result.put("redirectUrl", "/game/retro");
                return ResponseEntity.ok(result);
            }

            // 이전 설정 추출
            String nickname = previous.getNickname();
            int totalRounds = previous.getTotalRounds();
            GameSettings settings = gameSessionService.parseSettings(previous.getSettings());

            // 새 GameSession 생성
            GameSession session = new GameSession();
            session.setSessionUuid(UUID.randomUUID().toString());
            session.setNickname(nickname);
            session.setGameType(GameSession.GameType.RETRO_GUESS);
            session.setGameMode(GameSession.GameMode.RANDOM);
            session.setTotalRounds(totalRounds);
            session.setStatus(GameSession.GameStatus.PLAYING);
            session.setSettings(previous.getSettings());

            // 로그인한 회원인 경우 연결
            Long memberId = userDetails != null ? userDetails.getMemberId() : null;
            if (memberId != null) {
                memberService.findById(memberId).ifPresent(session::setMember);
            }

            // 레트로 곡 목록 가져오기 (YouTube 검증 포함)
            SongService.ValidatedSongsResult validatedResult =
                    songService.getRandomRetroSongsWithValidation(totalRounds, settings);
            List<Song> songs = validatedResult.getSongs();

            if (songs.isEmpty()) {
                result.put("success", false);
                result.put("message", "레트로 곡이 부족합니다.");
                result.put("redirectUrl", "/game/retro");
                return ResponseEntity.ok(result);
            }

            // 실제 생성된 라운드 수로 업데이트
            int actualRounds = songs.size();
            session.setTotalRounds(actualRounds);

            GameSession savedSession = gameSessionService.save(session);

            for (int i = 0; i < songs.size(); i++) {
                GameRound round = new GameRound();
                round.setGameSession(savedSession);
                round.setRoundNumber(i + 1);
                round.setSong(songs.get(i));
                round.setGenre(songs.get(i).getGenre());
                round.setPlayStartTime(songs.get(i).getStartTime());
                round.setPlayDuration(songs.get(i).getPlayDuration());
                round.setStatus(GameRound.RoundStatus.WAITING);
                savedSession.getRounds().add(round);
            }

            gameSessionService.save(savedSession);

            // 세션에 정보 저장
            httpSession.setAttribute("retroSessionId", savedSession.getId());
            httpSession.setAttribute("retroNickname", nickname);
            httpSession.setAttribute("retroPlayedSongIds", new ArrayList<Long>());
            httpSession.setAttribute("retroScore", 0);

            result.put("success", true);
            result.put("sessionId", savedSession.getId());
            result.put("actualRounds", actualRounds);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "재시작 중 오류가 발생했습니다: " + e.getMessage());
            result.put("redirectUrl", "/game/retro");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 시간 기반 점수 계산
     * 0초 이하(노래 시작 전): 100점 (고수 인정)
     * 5초까지: 100점, 8초까지: 90점, 12초까지: 80점, 15초까지: 70점, 이후: 60점
     */
    private int calculateScoreByTime(Double answerTimeSec) {
        if (answerTimeSec == null || answerTimeSec <= 5.0) {
            return 100;
        } else if (answerTimeSec <= 8.0) {
            return 90;
        } else if (answerTimeSec <= 12.0) {
            return 80;
        } else if (answerTimeSec <= 15.0) {
            return 70;
        } else {
            return 60;
        }
    }
}
