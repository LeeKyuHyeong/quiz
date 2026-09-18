package com.kh.game.controller.client;

import com.kh.game.entity.Genre;
import com.kh.game.entity.GenreChallengeDifficulty;
import com.kh.game.entity.GenreChallengeRecord;
import com.kh.game.entity.GameRound;
import com.kh.game.entity.GameSession;
import com.kh.game.entity.Member;
import com.kh.game.repository.GenreRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GenreChallengeService;
import com.kh.game.service.MemberService;
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
@RequestMapping("/game/genre-challenge")
@RequiredArgsConstructor
@Slf4j
public class GameGenreChallengeController {

    private final GenreChallengeService genreChallengeService;
    private final SongService songService;
    private final MemberService memberService;
    private final GenreRepository genreRepository;

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

        // 난이도 정보 전달 (설정-UI 동기화)
        GenreChallengeDifficulty normal = GenreChallengeDifficulty.NORMAL;
        GenreChallengeDifficulty hardcore = GenreChallengeDifficulty.HARDCORE;

        model.addAttribute("normalPlaySec", normal.getPlayTimeMs() / 1000);
        model.addAttribute("normalAnswerSec", normal.getAnswerTimeMs() / 1000);
        model.addAttribute("normalLives", normal.getInitialLives());

        model.addAttribute("hardcorePlaySec", hardcore.getPlayTimeMs() / 1000);
        model.addAttribute("hardcoreAnswerSec", hardcore.getAnswerTimeMs() / 1000);
        model.addAttribute("hardcoreLives", hardcore.getInitialLives());

        return "client/game/genre-challenge/setup";
    }

    /**
     * 장르 목록 조회 (곡 수 포함) - 50곡 이상 장르만
     */
    @GetMapping("/genres")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getGenresWithCount() {
        List<Map<String, Object>> genres = songService.getGenresWithSongCountMinimum(GenreChallengeService.MIN_SONG_COUNT);
        return ResponseEntity.ok(genres);
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
        String genreCode = (String) request.get("genreCode");
        String difficultyStr = (String) request.get("difficulty");

        // 난이도 파싱 (기본값: NORMAL)
        GenreChallengeDifficulty difficulty = GenreChallengeDifficulty.fromString(difficultyStr);

        if (nickname == null || nickname.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "닉네임을 입력해주세요");
            return ResponseEntity.badRequest().body(result);
        }

        if (genreCode == null || genreCode.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "장르를 선택해주세요");
            return ResponseEntity.badRequest().body(result);
        }

        // 장르 존재 확인
        Genre genre = genreRepository.findByCode(genreCode).orElse(null);
        if (genre == null) {
            result.put("success", false);
            result.put("message", "존재하지 않는 장르입니다");
            return ResponseEntity.badRequest().body(result);
        }

        // 곡 수 확인 (최소 50곡 필요)
        int songCount = songService.getSongCountByGenreCode(genreCode);
        if (songCount < GenreChallengeService.MIN_SONG_COUNT) {
            result.put("success", false);
            result.put("message", String.format("장르 챌린지는 %d곡 이상의 장르만 가능합니다 (현재 %d곡)",
                GenreChallengeService.MIN_SONG_COUNT, songCount));
            return ResponseEntity.badRequest().body(result);
        }

        // 로그인 회원 확인
        Member member = null;
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            member = memberService.findById(memberId).orElse(null);
        }

        // 게임 세션 생성 (난이도 포함)
        GameSession session = genreChallengeService.startChallenge(member, nickname.trim(), genreCode, difficulty);

        // HTTP 세션에 저장
        httpSession.setAttribute("genreChallengeSessionId", session.getId());
        httpSession.setAttribute("genreChallengeNickname", nickname.trim());
        httpSession.setAttribute("genreChallengeGenreCode", genreCode);
        httpSession.setAttribute("genreChallengeGenreName", genre.getName());
        httpSession.setAttribute("genreChallengeDifficulty", difficulty.name());

        result.put("success", true);
        result.put("sessionId", session.getId());
        result.put("genreCode", genreCode);
        result.put("genreName", genre.getName());
        result.put("totalRounds", session.getTotalRounds());
        result.put("remainingLives", session.getRemainingLives());

        // 난이도 설정 정보 추가
        result.put("difficulty", difficulty.name());
        result.put("playTimeMs", difficulty.getPlayTimeMs());
        result.put("answerTimeMs", difficulty.getAnswerTimeMs());
        result.put("totalTimeMs", difficulty.getTotalTimeMs());
        result.put("initialLives", difficulty.getInitialLives());
        result.put("isRanked", difficulty.isRanked());

        return ResponseEntity.ok(result);
    }

    /**
     * 게임 진행 페이지
     */
    @GetMapping("/play")
    public String play(HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("genreChallengeSessionId");
        if (sessionId == null) {
            return "redirect:/game/genre-challenge";
        }

        String nickname = (String) httpSession.getAttribute("genreChallengeNickname");
        String genreCode = (String) httpSession.getAttribute("genreChallengeGenreCode");
        String genreName = (String) httpSession.getAttribute("genreChallengeGenreName");
        String difficultyStr = (String) httpSession.getAttribute("genreChallengeDifficulty");
        GenreChallengeDifficulty difficulty = GenreChallengeDifficulty.fromString(difficultyStr);

        model.addAttribute("sessionId", sessionId);
        model.addAttribute("nickname", nickname);
        model.addAttribute("genreCode", genreCode);
        model.addAttribute("genreName", genreName);

        // 난이도 정보 추가
        model.addAttribute("difficulty", difficulty.name());
        model.addAttribute("difficultyName", difficulty.getDisplayName());
        model.addAttribute("playTimeMs", difficulty.getPlayTimeMs());
        model.addAttribute("answerTimeMs", difficulty.getAnswerTimeMs());
        model.addAttribute("totalTimeMs", difficulty.getTotalTimeMs());
        model.addAttribute("initialLives", difficulty.getInitialLives());
        model.addAttribute("isRanked", difficulty.isRanked());

        return "client/game/genre-challenge/play";
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

        Long sessionId = (Long) httpSession.getAttribute("genreChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        GameSession session = genreChallengeService.getSession(sessionId);
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
        GenreChallengeDifficulty difficulty = genreChallengeService.getDifficultyFromSession(session);

        result.put("success", true);
        result.put("roundNumber", roundNumber);
        result.put("totalRounds", session.getTotalRounds());
        result.put("remainingLives", session.getRemainingLives());
        result.put("correctCount", session.getCorrectCount());
        result.put("currentCombo", session.getCurrentCombo() != null ? session.getCurrentCombo() : 0);
        result.put("maxCombo", session.getMaxCombo() != null ? session.getMaxCombo() : 0);
        result.put("initialLives", difficulty.getInitialLives());

        // 난이도 설정
        result.put("playTimeMs", difficulty.getPlayTimeMs());
        result.put("answerTimeMs", difficulty.getAnswerTimeMs());
        result.put("totalTimeMs", difficulty.getTotalTimeMs());

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

        Long sessionId = (Long) httpSession.getAttribute("genreChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        int roundNumber = ((Number) request.get("roundNumber")).intValue();
        String answer = (String) request.get("answer");
        long answerTimeMs = ((Number) request.get("answerTimeMs")).longValue();

        GenreChallengeService.AnswerResult answerResult =
                genreChallengeService.processAnswer(sessionId, roundNumber, answer, answerTimeMs);

        result.put("success", true);
        result.put("isCorrect", answerResult.isCorrect());
        result.put("isTimeout", answerResult.isTimeout());
        result.put("correctAnswer", answerResult.correctAnswer());
        result.put("remainingLives", answerResult.remainingLives());
        result.put("correctCount", answerResult.correctCount());
        result.put("completedRounds", answerResult.completedRounds());
        result.put("totalRounds", answerResult.totalRounds());
        result.put("currentCombo", answerResult.currentCombo());
        result.put("maxCombo", answerResult.maxCombo());
        result.put("isGameOver", answerResult.isGameOver());
        result.put("gameOverReason", answerResult.gameOverReason());

        // 게임 종료 시 결과 페이지용 데이터 추가
        if (answerResult.isGameOver()) {
            String genreCode = (String) httpSession.getAttribute("genreChallengeGenreCode");
            String genreName = (String) httpSession.getAttribute("genreChallengeGenreName");
            String difficultyStr = (String) httpSession.getAttribute("genreChallengeDifficulty");
            GenreChallengeDifficulty difficulty = GenreChallengeDifficulty.fromString(difficultyStr);
            GameSession session = genreChallengeService.getSession(sessionId);

            result.put("resultData", Map.of(
                "genreCode", genreCode != null ? genreCode : "",
                "genreName", genreName != null ? genreName : "",
                "difficulty", difficulty.name(),
                "difficultyName", difficulty.getDisplayName(),
                "difficultyEmoji", difficulty.getBadgeEmoji(),
                "isRanked", difficulty.isRanked(),
                "playTimeSeconds", session != null ? session.getPlayTimeSeconds() : 0
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

        Long sessionId = (Long) httpSession.getAttribute("genreChallengeSessionId");
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "세션이 만료되었습니다");
            return ResponseEntity.badRequest().body(result);
        }

        int roundNumber = ((Number) request.get("roundNumber")).intValue();

        GenreChallengeService.AnswerResult answerResult =
                genreChallengeService.processTimeout(sessionId, roundNumber);

        result.put("success", true);
        result.put("isCorrect", false);
        result.put("isTimeout", true);
        result.put("correctAnswer", answerResult.correctAnswer());
        result.put("remainingLives", answerResult.remainingLives());
        result.put("correctCount", answerResult.correctCount());
        result.put("completedRounds", answerResult.completedRounds());
        result.put("totalRounds", answerResult.totalRounds());
        result.put("currentCombo", answerResult.currentCombo());
        result.put("maxCombo", answerResult.maxCombo());
        result.put("isGameOver", answerResult.isGameOver());
        result.put("gameOverReason", answerResult.gameOverReason());

        // 게임 종료 시 결과 페이지용 데이터 추가
        if (answerResult.isGameOver()) {
            String genreCode = (String) httpSession.getAttribute("genreChallengeGenreCode");
            String genreName = (String) httpSession.getAttribute("genreChallengeGenreName");
            String difficultyStr = (String) httpSession.getAttribute("genreChallengeDifficulty");
            GenreChallengeDifficulty difficulty = GenreChallengeDifficulty.fromString(difficultyStr);
            GameSession session = genreChallengeService.getSession(sessionId);

            result.put("resultData", Map.of(
                "genreCode", genreCode != null ? genreCode : "",
                "genreName", genreName != null ? genreName : "",
                "difficulty", difficulty.name(),
                "difficultyName", difficulty.getDisplayName(),
                "difficultyEmoji", difficulty.getBadgeEmoji(),
                "isRanked", difficulty.isRanked(),
                "playTimeSeconds", session != null ? session.getPlayTimeSeconds() : 0
            ));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 결과 페이지
     */
    @GetMapping("/result")
    public String result(@AuthenticationPrincipal CustomUserDetails userDetails, HttpSession httpSession, Model model) {
        Long sessionId = (Long) httpSession.getAttribute("genreChallengeSessionId");

        // 세션이 없으면 백업 데이터 사용 모드로 렌더링
        if (sessionId == null) {
            model.addAttribute("useBackup", true);
            return "client/game/genre-challenge/result";
        }

        GameSession session = genreChallengeService.getSession(sessionId);
        if (session == null) {
            log.warn("Genre Challenge 결과 조회 실패: sessionId={}", sessionId);
            model.addAttribute("useBackup", true);
            return "client/game/genre-challenge/result";
        }

        // 디버그: rounds 로딩 확인
        List<GameRound> rounds = session.getRounds();
        log.info("Genre Challenge 결과 조회: sessionId={}, roundsSize={}",
                sessionId, rounds != null ? rounds.size() : "null");

        model.addAttribute("useBackup", false);

        String genreCode = (String) httpSession.getAttribute("genreChallengeGenreCode");
        String genreName = (String) httpSession.getAttribute("genreChallengeGenreName");
        String difficultyStr = (String) httpSession.getAttribute("genreChallengeDifficulty");
        GenreChallengeDifficulty difficulty = GenreChallengeDifficulty.fromString(difficultyStr);

        model.addAttribute("session", session);
        model.addAttribute("rounds", rounds);
        model.addAttribute("genreCode", genreCode);
        model.addAttribute("genreName", genreName);
        model.addAttribute("correctCount", session.getCorrectCount());
        model.addAttribute("totalRounds", session.getTotalRounds());
        model.addAttribute("maxCombo", session.getMaxCombo() != null ? session.getMaxCombo() : 0);
        model.addAttribute("playTimeSeconds", session.getPlayTimeSeconds());

        // 난이도 정보
        model.addAttribute("difficulty", difficulty.name());
        model.addAttribute("difficultyName", difficulty.getDisplayName());
        model.addAttribute("difficultyEmoji", difficulty.getBadgeEmoji());
        model.addAttribute("isRanked", difficulty.isRanked());

        // 장르별 공식 랭킹 (하드코어만)
        List<GenreChallengeRecord> ranking = genreChallengeService.getGenreRankingByCode(genreCode, 10);
        model.addAttribute("ranking", ranking);

        // 내 기록
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            Member member = memberService.findById(memberId).orElse(null);
            if (member != null) {
                Genre genre = genreRepository.findByCode(genreCode).orElse(null);
                if (genre != null) {
                    // 현재 난이도 기록
                    genreChallengeService.getMemberRecord(member, genre, difficulty).ifPresent(record -> {
                        model.addAttribute("myRecord", record);
                    });
                }
            }
        }

        // 로그인 여부
        model.addAttribute("isLoggedIn", memberId != null);

        // 재시작용 세션 ID 전달 (세션 정리 전에 저장)
        model.addAttribute("gameSessionId", sessionId);

        // 세션 정리
        httpSession.removeAttribute("genreChallengeSessionId");
        httpSession.removeAttribute("genreChallengeNickname");
        httpSession.removeAttribute("genreChallengeGenreCode");
        httpSession.removeAttribute("genreChallengeGenreName");
        httpSession.removeAttribute("genreChallengeDifficulty");

        return "client/game/genre-challenge/result";
    }

    /**
     * 장르별 랭킹 조회
     */
    @GetMapping("/ranking/{genreCode}")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getGenreRanking(
            @PathVariable String genreCode) {

        List<GenreChallengeRecord> records = genreChallengeService.getGenreRankingByCode(genreCode, 20);

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (GenreChallengeRecord record : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("rank", rank++);
            item.put("nickname", record.getMember().getNickname());
            item.put("correctCount", record.getCorrectCount());
            item.put("totalSongs", Math.min(record.getTotalSongs(), GenreChallengeService.MAX_SONG_COUNT));
            item.put("maxCombo", record.getMaxCombo());
            item.put("bestTimeMs", record.getBestTimeMs());
            item.put("achievedAt", record.getAchievedAt());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 장르 정보 조회 (설정 화면용)
     * - 1위 기록
     * - 내 기록 (로그인 시)
     */
    @GetMapping("/info/{genreCode}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getGenreChallengeInfo(
            @PathVariable String genreCode,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpSession httpSession) {

        Map<String, Object> result = new HashMap<>();

        // 장르 정보
        Genre genre = genreRepository.findByCode(genreCode).orElse(null);
        if (genre == null) {
            return ResponseEntity.ok(result);
        }
        result.put("genreName", genre.getName());
        result.put("songCount", songService.getSongCountByGenreCode(genreCode));

        // 1위 기록 조회
        List<GenreChallengeRecord> topRecords = genreChallengeService.getGenreRankingByCode(genreCode, 1);
        if (!topRecords.isEmpty()) {
            GenreChallengeRecord top = topRecords.get(0);
            Map<String, Object> topInfo = new HashMap<>();
            topInfo.put("nickname", top.getMember().getNickname());
            topInfo.put("correctCount", top.getCorrectCount());
            topInfo.put("totalSongs", Math.min(top.getTotalSongs(), GenreChallengeService.MAX_SONG_COUNT));
            topInfo.put("maxCombo", top.getMaxCombo());
            topInfo.put("bestTimeMs", top.getBestTimeMs());
            result.put("topRecord", topInfo);
        }

        // 내 기록 조회 (로그인 시, 하드코어 기록)
        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        if (memberId != null) {
            memberService.findById(memberId).ifPresent(member -> {
                genreChallengeService.getMemberRecord(member, genre, GenreChallengeDifficulty.HARDCORE)
                        .ifPresent(record -> {
                            Map<String, Object> myInfo = new HashMap<>();
                            myInfo.put("correctCount", record.getCorrectCount());
                            myInfo.put("totalSongs", Math.min(record.getTotalSongs(), GenreChallengeService.MAX_SONG_COUNT));
                            myInfo.put("maxCombo", record.getMaxCombo());
                            myInfo.put("bestTimeMs", record.getBestTimeMs());
                            result.put("myRecord", myInfo);
                        });
            });
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 게임 포기
     */
    @PostMapping("/end")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> endGame(HttpSession httpSession) {
        Map<String, Object> result = new HashMap<>();

        // 세션 정리
        httpSession.removeAttribute("genreChallengeSessionId");
        httpSession.removeAttribute("genreChallengeNickname");
        httpSession.removeAttribute("genreChallengeGenreCode");
        httpSession.removeAttribute("genreChallengeGenreName");
        httpSession.removeAttribute("genreChallengeDifficulty");

        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    /**
     * 같은 장르로 다시 도전
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
            GameSession previous = genreChallengeService.getSession(previousSessionId);
            if (previous == null) {
                result.put("success", false);
                result.put("message", "이전 게임 정보를 찾을 수 없습니다.");
                result.put("redirectUrl", "/game/genre-challenge");
                return ResponseEntity.ok(result);
            }

            // 이전 설정 추출
            String nickname = previous.getNickname();
            String genreCode = previous.getChallengeGenreCode();
            GenreChallengeDifficulty difficulty = genreChallengeService.getDifficultyFromSession(previous);

            if (genreCode == null || genreCode.isEmpty()) {
                result.put("success", false);
                result.put("message", "장르 정보를 찾을 수 없습니다.");
                result.put("redirectUrl", "/game/genre-challenge");
                return ResponseEntity.ok(result);
            }

            // 장르 정보 조회
            Genre genre = genreRepository.findByCode(genreCode).orElse(null);
            if (genre == null) {
                result.put("success", false);
                result.put("message", "존재하지 않는 장르입니다.");
                result.put("redirectUrl", "/game/genre-challenge");
                return ResponseEntity.ok(result);
            }

            // 로그인 회원 확인
            Member member = null;
            Long memberId = userDetails != null ? userDetails.getMemberId() : null;
            if (memberId != null) {
                member = memberService.findById(memberId).orElse(null);
            }

            // 새 게임 세션 생성
            GameSession session = genreChallengeService.startChallenge(member, nickname, genreCode, difficulty);

            // HTTP 세션에 저장
            httpSession.setAttribute("genreChallengeSessionId", session.getId());
            httpSession.setAttribute("genreChallengeNickname", nickname);
            httpSession.setAttribute("genreChallengeGenreCode", genreCode);
            httpSession.setAttribute("genreChallengeGenreName", genre.getName());
            httpSession.setAttribute("genreChallengeDifficulty", difficulty.name());

            result.put("success", true);
            result.put("sessionId", session.getId());
            result.put("genreCode", genreCode);
            result.put("genreName", genre.getName());
            result.put("totalRounds", session.getTotalRounds());
            result.put("remainingLives", session.getRemainingLives());
            result.put("difficulty", difficulty.name());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("장르 챌린지 재시작 오류", e);
            result.put("success", false);
            result.put("message", "재시작 중 오류가 발생했습니다: " + e.getMessage());
            result.put("redirectUrl", "/game/genre-challenge");
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 홈 페이지용 장르별 TOP1 기록 조회 API
     * 정렬: correctCount DESC → bestTimeMs ASC
     */
    @GetMapping("/top-genres")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getTopGenresRanking() {
        List<Map<String, Object>> result = genreChallengeService.getTopGenresWithTopRecord();
        return ResponseEntity.ok(result);
    }
}
