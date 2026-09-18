package com.kh.game.controller.client;

import com.kh.game.entity.FanChallengeDifficulty;
import com.kh.game.entity.FanChallengeRecord;
import com.kh.game.entity.FanChallengeStageConfig;
import com.kh.game.entity.GameRound;
import com.kh.game.entity.GameSession;
import com.kh.game.entity.Member;
import com.kh.game.service.FanChallengeService;
import com.kh.game.service.FanChallengeStageService;
import com.kh.game.service.MemberService;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.SongPopularityVoteService;
import com.kh.game.service.SongService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/game/fan-challenge")
@RequiredArgsConstructor
@Slf4j
public class GameFanChallengeController {

    private final FanChallengeService fanChallengeService;
    private final FanChallengeStageService stageService;
    private final SongService songService;
    private final MemberService memberService;
    private final SongPopularityVoteService songPopularityVoteService;

    /**
     * 설정 페이지
     */
    @GetMapping
    public String setup(@AuthenticationPrincipal CustomUserDetails userDetails, HttpSession httpSession, Model model) {
        // 로그인 상태 확인
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            memberService.findById(memberId).ifPresent(member -> {
                model.addAttribute("member", member);
                model.addAttribute("nickname", member.getNickname());
            });
        }

        return "client/game/fan-challenge/setup";
    }

    /**
     * 아티스트 목록 조회 (곡 수 포함) - 30곡 이상 아티스트만
     */
    @GetMapping("/artists")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getArtistsWithCount() {
        List<Map<String, Object>> artists = songService.getArtistsWithCountForFanChallenge();
        // 최소 30곡 이상인 아티스트만 필터링
        artists.removeIf(artist -> ((Number) artist.get("count")).intValue() < FanChallengeService.CHALLENGE_SONG_COUNT);
        return ResponseEntity.ok(artists);
    }

    /**
     * 아티스트 검색
     */
    @GetMapping("/artists/search")
    @ResponseBody
    public ResponseEntity<List<String>> searchArtists(@RequestParam String keyword) {
        List<String> artists = songService.searchArtists(keyword);
        return ResponseEntity.ok(artists);
    }

    /**
     * 아티스트별 도전 가능한 단계 조회 (HARDCORE 모드용)
     */
    @GetMapping("/stages/{artist}")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getArtistStages(@PathVariable String artist) {
        int songCount = songService.getSongCountByArtist(artist);
        List<FanChallengeStageConfig> allActiveStages = stageService.getActiveStages();

        List<Map<String, Object>> result = allActiveStages.stream()
                .map(stage -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("level", stage.getStageLevel());
                    item.put("name", stage.getStageName());
                    item.put("emoji", stage.getStageEmoji());
                    item.put("requiredSongs", stage.getRequiredSongs());
                    item.put("available", songCount >= stage.getRequiredSongs());
                    return item;
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 게임 시작
     */
    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startChallenge(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        String nickname = (String) request.get("nickname");
        String artist = (String) request.get("artist");
        String difficultyStr = (String) request.get("difficulty");
        Integer stageLevel = request.get("stageLevel") != null
                ? ((Number) request.get("stageLevel")).intValue() : 1;

        // 난이도 파싱 (기본값: NORMAL)
        FanChallengeDifficulty difficulty = FanChallengeDifficulty.fromString(difficultyStr);

        if (nickname == null || nickname.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "닉네임을 입력해주세요");
            return ResponseEntity.badRequest().body(result);
        }

        if (artist == null || artist.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "아티스트를 선택해주세요");
            return ResponseEntity.badRequest().body(result);
        }

        // 곡 수 확인
        int songCount = songService.getSongCountByArtist(artist);

        // NORMAL 모드: 20곡 고정
        // HARDCORE 모드: 단계별 곡 수 확인
        int requiredSongs;
        if (difficulty == FanChallengeDifficulty.NORMAL) {
            requiredSongs = FanChallengeService.CHALLENGE_SONG_COUNT;
            stageLevel = 1; // NORMAL은 항상 1단계
        } else {
            // 단계 설정 조회
            FanChallengeStageConfig stageConfig = stageService.getStageConfig(stageLevel)
                    .orElse(null);
            if (stageConfig == null || !Boolean.TRUE.equals(stageConfig.getIsActive())) {
                result.put("success", false);
                result.put("message", "유효하지 않은 단계입니다.");
                return ResponseEntity.badRequest().body(result);
            }
            requiredSongs = stageConfig.getRequiredSongs();
        }

        if (songCount < requiredSongs) {
            result.put("success", false);
            result.put("message", String.format("이 아티스트는 %d단계(%d곡)에 도전할 수 없습니다 (현재 %d곡)",
                    stageLevel, requiredSongs, songCount));
            return ResponseEntity.badRequest().body(result);
        }

        // 로그인 회원 확인
        Member member = null;
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            member = memberService.findById(memberId).orElse(null);
        }

        // 게임 세션 생성 (난이도 + 단계 포함)
        GameSession session = fanChallengeService.startChallenge(member, nickname.trim(), artist, difficulty, stageLevel);

        // HTTP 세션에 저장
        httpSession.setAttribute("fanChallengeSessionId", session.getId());
        httpSession.setAttribute("fanChallengeNickname", nickname.trim());
        httpSession.setAttribute("fanChallengeArtist", artist);
        httpSession.setAttribute("fanChallengeDifficulty", difficulty.name());
        httpSession.setAttribute("fanChallengeStageLevel", stageLevel);

        result.put("success", true);
        result.put("sessionId", session.getId());
        result.put("artist", artist);
        result.put("totalRounds", session.getTotalRounds());
        result.put("remainingLives", session.getRemainingLives());

        // 난이도 설정 정보 추가
        result.put("difficulty", difficulty.name());
        result.put("playTimeMs", difficulty.getPlayTimeMs());
        result.put("answerTimeMs", difficulty.getAnswerTimeMs());
        result.put("initialLives", difficulty.getInitialLives());
        result.put("showChosungHint", difficulty.isShowChosungHint());
        result.put("isRanked", difficulty.isRanked());
        result.put("stageLevel", stageLevel);

        return ResponseEntity.ok(result);
    }

    /**
     * 게임 진행 페이지
     */
    @GetMapping("/play")
    public String play(HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("fanChallengeSessionId");
        if (sessionId == null) {
            return "redirect:/game/fan-challenge";
        }

        String nickname = (String) httpSession.getAttribute("fanChallengeNickname");
        String artist = (String) httpSession.getAttribute("fanChallengeArtist");
        String difficultyStr = (String) httpSession.getAttribute("fanChallengeDifficulty");
        FanChallengeDifficulty difficulty = FanChallengeDifficulty.fromString(difficultyStr);

        model.addAttribute("sessionId", sessionId);
        model.addAttribute("nickname", nickname);
        model.addAttribute("artist", artist);

        // 난이도 정보 추가
        model.addAttribute("difficulty", difficulty.name());
        model.addAttribute("difficultyName", difficulty.getDisplayName());
        model.addAttribute("playTimeMs", difficulty.getPlayTimeMs());
        model.addAttribute("answerTimeMs", difficulty.getAnswerTimeMs());
        model.addAttribute("initialLives", difficulty.getInitialLives());
        model.addAttribute("showChosungHint", difficulty.isShowChosungHint());
        model.addAttribute("isRanked", difficulty.isRanked());

        return "client/game/fan-challenge/play";
    }

    /**
     * 라운드 정보 조회
     */
    @GetMapping("/round/{roundNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRound(
            @PathVariable int roundNumber,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        Long sessionId = (Long) httpSession.getAttribute("fanChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        GameSession session = fanChallengeService.getSession(sessionId);
        if (session == null) {
            result.put("success", false);
            result.put("message", "세션을 찾을 수 없습니다");
            return ResponseEntity.badRequest().body(result);
        }

        GameRound round = session.getRounds().stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .orElse(null);

        if (round == null) {
            result.put("success", false);
            result.put("message", "라운드를 찾을 수 없습니다");
            return ResponseEntity.badRequest().body(result);
        }

        // 난이도 정보 가져오기
        FanChallengeDifficulty difficulty = fanChallengeService.getDifficultyFromSession(session);

        result.put("success", true);
        result.put("roundNumber", roundNumber);
        result.put("totalRounds", session.getTotalRounds());
        result.put("remainingLives", session.getRemainingLives());
        result.put("correctCount", session.getCorrectCount());
        result.put("initialLives", difficulty.getInitialLives());

        // 난이도 설정
        result.put("playTimeMs", difficulty.getPlayTimeMs());
        result.put("answerTimeMs", difficulty.getAnswerTimeMs());
        result.put("showChosungHint", difficulty.isShowChosungHint());

        // 노래 정보
        Map<String, Object> songInfo = new HashMap<>();
        if (round.getSong().getYoutubeVideoId() != null) {
            songInfo.put("youtubeVideoId", round.getSong().getYoutubeVideoId());
        }
        if (round.getSong().getFilePath() != null) {
            songInfo.put("filePath", round.getSong().getFilePath());
        }
        songInfo.put("startTime", round.getPlayStartTime() != null ? round.getPlayStartTime() : 0);
        songInfo.put("playDuration", round.getPlayDuration() != null ? round.getPlayDuration() : 30);

        // 초성 힌트 (입문 모드일 때만)
        if (difficulty.isShowChosungHint()) {
            songInfo.put("chosungHint", fanChallengeService.extractChosung(round.getSong().getTitle()));
        }

        result.put("song", songInfo);

        return ResponseEntity.ok(result);
    }

    /**
     * 정답 제출
     */
    @PostMapping("/answer")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitAnswer(
            @RequestBody Map<String, Object> request,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        Long sessionId = (Long) httpSession.getAttribute("fanChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        int roundNumber = ((Number) request.get("roundNumber")).intValue();
        String answer = (String) request.get("answer");
        long answerTimeMs = ((Number) request.get("answerTimeMs")).longValue();

        FanChallengeService.AnswerResult answerResult =
                fanChallengeService.processAnswer(sessionId, roundNumber, answer, answerTimeMs);

        result.put("success", true);
        result.put("isCorrect", answerResult.isCorrect());
        result.put("isTimeout", answerResult.isTimeout());
        result.put("correctAnswer", answerResult.correctAnswer());
        result.put("remainingLives", answerResult.remainingLives());
        result.put("correctCount", answerResult.correctCount());
        result.put("completedRounds", answerResult.completedRounds());
        result.put("totalRounds", answerResult.totalRounds());
        result.put("isGameOver", answerResult.isGameOver());
        result.put("gameOverReason", answerResult.gameOverReason());

        // 게임 종료 시 결과 페이지용 데이터 추가
        if (answerResult.isGameOver()) {
            String artist = (String) httpSession.getAttribute("fanChallengeArtist");
            String difficultyStr = (String) httpSession.getAttribute("fanChallengeDifficulty");
            FanChallengeDifficulty difficulty = FanChallengeDifficulty.fromString(difficultyStr);
            GameSession session = fanChallengeService.getSession(sessionId);

            result.put("resultData", Map.of(
                "artist", artist != null ? artist : "",
                "difficulty", difficulty.name(),
                "difficultyName", difficulty.getDisplayName(),
                "difficultyEmoji", difficulty.getBadgeEmoji(),
                "isRanked", difficulty.isRanked(),
                "playTimeSeconds", session != null ? session.getPlayTimeSeconds() : 0,
                "isPerfectClear", answerResult.correctCount() == answerResult.totalRounds()
            ));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 시간 초과 처리
     */
    @PostMapping("/timeout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handleTimeout(
            @RequestBody Map<String, Object> request,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        Long sessionId = (Long) httpSession.getAttribute("fanChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        int roundNumber = ((Number) request.get("roundNumber")).intValue();

        FanChallengeService.AnswerResult answerResult =
                fanChallengeService.processTimeout(sessionId, roundNumber);

        result.put("success", true);
        result.put("isCorrect", false);
        result.put("isTimeout", true);
        result.put("correctAnswer", answerResult.correctAnswer());
        result.put("remainingLives", answerResult.remainingLives());
        result.put("correctCount", answerResult.correctCount());
        result.put("completedRounds", answerResult.completedRounds());
        result.put("totalRounds", answerResult.totalRounds());
        result.put("isGameOver", answerResult.isGameOver());
        result.put("gameOverReason", answerResult.gameOverReason());

        // 게임 종료 시 결과 페이지용 데이터 추가
        if (answerResult.isGameOver()) {
            String artist = (String) httpSession.getAttribute("fanChallengeArtist");
            String difficultyStr = (String) httpSession.getAttribute("fanChallengeDifficulty");
            FanChallengeDifficulty difficulty = FanChallengeDifficulty.fromString(difficultyStr);
            GameSession session = fanChallengeService.getSession(sessionId);

            result.put("resultData", Map.of(
                "artist", artist != null ? artist : "",
                "difficulty", difficulty.name(),
                "difficultyName", difficulty.getDisplayName(),
                "difficultyEmoji", difficulty.getBadgeEmoji(),
                "isRanked", difficulty.isRanked(),
                "playTimeSeconds", session != null ? session.getPlayTimeSeconds() : 0,
                "isPerfectClear", answerResult.correctCount() == answerResult.totalRounds()
            ));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 결과 페이지
     */
    @GetMapping("/result")
    public String result(@AuthenticationPrincipal CustomUserDetails userDetails, HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("fanChallengeSessionId");

        // 세션이 없으면 백업 데이터 사용 모드로 렌더링
        if (sessionId == null) {
            model.addAttribute("useBackup", true);
            return "client/game/fan-challenge/result";
        }

        GameSession session = fanChallengeService.getSession(sessionId);
        if (session == null) {
            log.warn("Fan Challenge 결과 조회 실패: sessionId={}", sessionId);
            model.addAttribute("useBackup", true);
            return "client/game/fan-challenge/result";
        }

        // 디버그: rounds 로딩 확인
        List<GameRound> rounds = session.getRounds();
        log.info("Fan Challenge 결과 조회: sessionId={}, roundsSize={}",
                sessionId, rounds != null ? rounds.size() : "null");

        model.addAttribute("useBackup", false);

        String artist = (String) httpSession.getAttribute("fanChallengeArtist");
        String difficultyStr = (String) httpSession.getAttribute("fanChallengeDifficulty");
        FanChallengeDifficulty difficulty = FanChallengeDifficulty.fromString(difficultyStr);

        model.addAttribute("session", session);
        model.addAttribute("rounds", rounds); // rounds를 별도로 추가
        model.addAttribute("artist", artist);
        model.addAttribute("correctCount", session.getCorrectCount());
        model.addAttribute("totalRounds", session.getTotalRounds());
        model.addAttribute("isPerfectClear", session.getCorrectCount().equals(session.getTotalRounds()));
        model.addAttribute("playTimeSeconds", session.getPlayTimeSeconds());

        // 난이도 정보
        model.addAttribute("difficulty", difficulty.name());
        model.addAttribute("difficultyName", difficulty.getDisplayName());
        model.addAttribute("difficultyEmoji", difficulty.getBadgeEmoji());
        model.addAttribute("isRanked", difficulty.isRanked());

        // 아티스트 공식 랭킹 (하드코어만)
        List<FanChallengeRecord> ranking = fanChallengeService.getArtistRanking(artist, 10);
        model.addAttribute("ranking", ranking);

        // 내 기록 및 퍼펙트 뱃지
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            Member member = memberService.findById(memberId).orElse(null);
            if (member != null) {
                // 현재 난이도 기록
                fanChallengeService.getMemberRecord(member, artist, difficulty).ifPresent(record -> {
                    model.addAttribute("myRecord", record);
                });

                // 퍼펙트 클리어 뱃지 목록 (전체 난이도)
                List<FanChallengeRecord> perfectBadges = fanChallengeService.getPerfectBadges(member, artist);
                List<Map<String, Object>> badges = new ArrayList<>();
                boolean hasBadgeNormal = false;
                boolean hasBadgeHardcore = false;

                for (FanChallengeRecord badge : perfectBadges) {
                    Map<String, Object> badgeInfo = new HashMap<>();
                    badgeInfo.put("difficulty", badge.getDifficulty().name());
                    badgeInfo.put("difficultyName", badge.getDifficulty().getDisplayName());
                    badgeInfo.put("emoji", badge.getDifficulty().getBadgeEmoji());
                    badgeInfo.put("achievedAt", badge.getAchievedAt());
                    badges.add(badgeInfo);

                    // 난이도별 뱃지 보유 여부
                    if (badge.getDifficulty() == FanChallengeDifficulty.NORMAL) {
                        hasBadgeNormal = true;
                    } else if (badge.getDifficulty() == FanChallengeDifficulty.HARDCORE) {
                        hasBadgeHardcore = true;
                    }
                }
                model.addAttribute("perfectBadges", badges);
                model.addAttribute("hasBadgeNormal", hasBadgeNormal);
                model.addAttribute("hasBadgeHardcore", hasBadgeHardcore);

                // 곡 인기도 평가 기존 투표 조회
                List<Long> songIds = rounds.stream()
                        .filter(r -> r.getSong() != null)
                        .map(r -> r.getSong().getId())
                        .toList();
                Map<Long, Integer> existingVotes = songPopularityVoteService.getMemberVotesForSongs(member, songIds);
                model.addAttribute("existingVotes", existingVotes);
            }
        }

        // 로그인 여부
        model.addAttribute("isLoggedIn", memberId != null);

        // 재시작용 세션 ID 전달 (세션 정리 전에 저장)
        model.addAttribute("gameSessionId", sessionId);
        Integer stageLevel = (Integer) httpSession.getAttribute("fanChallengeStageLevel");
        model.addAttribute("stageLevel", stageLevel != null ? stageLevel : 1);

        // 세션 정리
        httpSession.removeAttribute("fanChallengeSessionId");
        httpSession.removeAttribute("fanChallengeNickname");
        httpSession.removeAttribute("fanChallengeArtist");
        httpSession.removeAttribute("fanChallengeDifficulty");
        httpSession.removeAttribute("fanChallengeStageLevel");

        return "client/game/fan-challenge/result";
    }

    /**
     * 아티스트별 랭킹 조회
     */
    @GetMapping("/ranking/{artist}")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getArtistRanking(
            @PathVariable String artist) {

        List<FanChallengeRecord> records = fanChallengeService.getArtistRanking(artist, 20);

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (FanChallengeRecord record : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("rank", rank++);
            item.put("nickname", record.getMember().getNickname());
            item.put("correctCount", record.getCorrectCount());
            item.put("totalSongs", record.getTotalSongs());
            item.put("isPerfectClear", record.getIsPerfectClear());
            item.put("bestTimeMs", record.getBestTimeMs());
            item.put("achievedAt", record.getAchievedAt());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 홈 페이지용 아티스트 TOP1 기록 조회 (HARDCORE 단계별)
     */
    @GetMapping("/top-artists")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getTopArtistsRanking(
            @RequestParam(required = false, defaultValue = "1") Integer stageLevel) {
        List<Map<String, Object>> result = fanChallengeService.getTopArtistsWithTopRecord(stageLevel);
        return ResponseEntity.ok(result);
    }

    /**
     * 홈 페이지용 활성화된 단계 목록 조회
     */
    @GetMapping("/active-stages")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getActiveStages() {
        List<FanChallengeStageConfig> activeStages = stageService.getActiveStages();

        List<Map<String, Object>> result = activeStages.stream()
                .map(stage -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("level", stage.getStageLevel());
                    item.put("name", stage.getStageName());
                    item.put("emoji", stage.getStageEmoji());
                    item.put("requiredSongs", stage.getRequiredSongs());
                    return item;
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 아티스트 챌린지 정보 조회 (설정 화면용)
     * - 1위 기록
     * - 내 기록 (로그인 시)
     */
    @GetMapping("/info/{artist}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArtistChallengeInfo(
            @PathVariable String artist,
            @RequestParam(required = false, defaultValue = "1") Integer stageLevel,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();
        result.put("stageLevel", stageLevel);

        // 1위 기록 조회 (단계별)
        List<FanChallengeRecord> topRecords = fanChallengeService.getArtistStageRanking(artist, stageLevel, 1);
        if (!topRecords.isEmpty()) {
            FanChallengeRecord top = topRecords.get(0);
            Map<String, Object> topInfo = new HashMap<>();
            topInfo.put("nickname", top.getMember().getNickname());
            topInfo.put("correctCount", top.getCorrectCount());
            topInfo.put("totalSongs", top.getTotalSongs());
            topInfo.put("isPerfectClear", top.getIsPerfectClear());
            topInfo.put("bestTimeMs", top.getBestTimeMs());
            topInfo.put("stageLevel", top.getStageLevel());
            result.put("topRecord", topInfo);
        }

        // 내 기록 조회 (로그인 시, 하드코어 기록 - 단계별)
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            memberService.findById(memberId).ifPresent(member -> {
                fanChallengeService.getMemberRecord(member, artist, FanChallengeDifficulty.HARDCORE, stageLevel)
                        .ifPresent(record -> {
                            Map<String, Object> myInfo = new HashMap<>();
                            myInfo.put("correctCount", record.getCorrectCount());
                            myInfo.put("totalSongs", record.getTotalSongs());
                            myInfo.put("isPerfectClear", record.getIsPerfectClear());
                            myInfo.put("bestTimeMs", record.getBestTimeMs());
                            myInfo.put("stageLevel", record.getStageLevel());
                            result.put("myRecord", myInfo);
                        });
            });
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 곡 인기도 평가 제출
     */
    @PostMapping("/api/song/{songId}/popularity-vote")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitPopularityVote(
            @PathVariable Long songId,
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        // 로그인 확인
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId == null) {
            result.put("success", false);
            result.put("message", "로그인이 필요합니다");
            return ResponseEntity.status(401).body(result);
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member == null) {
            result.put("success", false);
            result.put("message", "회원 정보를 찾을 수 없습니다");
            return ResponseEntity.status(401).body(result);
        }

        // rating 파싱
        int rating;
        try {
            rating = ((Number) request.get("rating")).intValue();
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "평가 값이 올바르지 않습니다");
            return ResponseEntity.badRequest().body(result);
        }

        // 투표 제출
        result = songPopularityVoteService.submitVote(songId, member, rating);

        if ((boolean) result.get("success")) {
            result.put("ratingLabel", SongPopularityVoteService.getRatingLabel(rating));
            return ResponseEntity.ok(result);
        } else if (result.get("alreadyVoted") != null && (boolean) result.get("alreadyVoted")) {
            return ResponseEntity.status(409).body(result); // Conflict
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 게임 포기
     */
    @PostMapping("/end")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> endGame(HttpSession httpSession) {
        Map<String, Object> result = new HashMap<>();

        // 세션 정리
        httpSession.removeAttribute("fanChallengeSessionId");
        httpSession.removeAttribute("fanChallengeNickname");
        httpSession.removeAttribute("fanChallengeArtist");
        httpSession.removeAttribute("fanChallengeDifficulty");

        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    /**
     * 같은 아티스트로 다시 도전
     */
    @PostMapping("/restart")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> restartGame(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        try {
            Long previousSessionId = Long.valueOf(request.get("previousSessionId").toString());

            // 이전 세션 조회
            GameSession previous = fanChallengeService.getSession(previousSessionId);
            if (previous == null) {
                result.put("success", false);
                result.put("message", "이전 게임 정보를 찾을 수 없습니다.");
                result.put("redirectUrl", "/game/fan-challenge");
                return ResponseEntity.ok(result);
            }

            // 이전 설정 추출
            String nickname = previous.getNickname();
            String artist = previous.getChallengeArtist();
            FanChallengeDifficulty difficulty = fanChallengeService.getDifficultyFromSession(previous);
            Integer stageLevel = fanChallengeService.getStageLevelFromSession(previous);

            if (artist == null || artist.isEmpty()) {
                result.put("success", false);
                result.put("message", "아티스트 정보를 찾을 수 없습니다.");
                result.put("redirectUrl", "/game/fan-challenge");
                return ResponseEntity.ok(result);
            }

            // 로그인 회원 확인
            Member member = null;
            Long memberId = userDetails != null ? userDetails.getMemberId() : null;
            if (memberId != null) {
                member = memberService.findById(memberId).orElse(null);
            }

            // 새 게임 세션 생성
            GameSession session = fanChallengeService.startChallenge(member, nickname, artist, difficulty, stageLevel);

            // HTTP 세션에 저장
            httpSession.setAttribute("fanChallengeSessionId", session.getId());
            httpSession.setAttribute("fanChallengeNickname", nickname);
            httpSession.setAttribute("fanChallengeArtist", artist);
            httpSession.setAttribute("fanChallengeDifficulty", difficulty.name());
            httpSession.setAttribute("fanChallengeStageLevel", stageLevel);

            result.put("success", true);
            result.put("sessionId", session.getId());
            result.put("artist", artist);
            result.put("totalRounds", session.getTotalRounds());
            result.put("remainingLives", session.getRemainingLives());
            result.put("difficulty", difficulty.name());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("팬 챌린지 재시작 오류", e);
            result.put("success", false);
            result.put("message", "재시작 중 오류가 발생했습니다: " + e.getMessage());
            result.put("redirectUrl", "/game/fan-challenge");
            return ResponseEntity.ok(result);
        }
    }
}
