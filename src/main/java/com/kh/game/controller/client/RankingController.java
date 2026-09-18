package com.kh.game.controller.client;

import com.kh.game.entity.GenreChallengeRecord;
import com.kh.game.entity.Member;
import com.kh.game.repository.GenreChallengeRecordRepository;
import com.kh.game.repository.GenreRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.FanChallengeService;
import com.kh.game.service.GameSessionService;
import com.kh.game.service.GenreChallengeService;
import com.kh.game.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class RankingController {

    private final MemberService memberService;
    private final GameSessionService gameSessionService;
    private final FanChallengeService fanChallengeService;
    private final GenreChallengeRecordRepository genreChallengeRecordRepository;
    private final GenreRepository genreRepository;

    // 랭킹 페이지
    @GetMapping("/ranking")
    public String rankingPage(Model model) {
        // 내가맞추기 랭킹
        model.addAttribute("guessScoreRanking", memberService.getGuessRankingByScore(20));
        model.addAttribute("guessAccuracyRanking", memberService.getGuessRankingByAccuracy(20));
        model.addAttribute("guessGamesRanking", memberService.getGuessRankingByGames(20));

        // 멀티게임 랭킹
        model.addAttribute("multiScoreRanking", memberService.getMultiRankingByScore(20));
        model.addAttribute("multiAccuracyRanking", memberService.getMultiRankingByAccuracy(20));
        model.addAttribute("multiGamesRanking", memberService.getMultiRankingByGames(20));

        // 주간 랭킹
        model.addAttribute("weeklyGuessRanking", memberService.getWeeklyGuessRankingByScore(20));

        // 최고 기록 랭킹
        model.addAttribute("guessBestRanking", memberService.getGuessBestScoreRanking(20));
        model.addAttribute("multiBestRanking", memberService.getMultiBestScoreRanking(20));

        // 30곡 최고점 랭킹 (점수 → 소요시간 순)
        model.addAttribute("weeklyBest30Ranking", gameSessionService.getWeeklyBest30RankingByDuration(50));
        model.addAttribute("monthlyBest30Ranking", gameSessionService.getMonthlyBest30RankingByDuration(50));
        model.addAttribute("allTimeBest30Ranking", gameSessionService.getAllTimeBest30RankingByDuration(50));

        return "client/ranking";
    }

    // 랭킹 API (모드별, 기간별 지원)
    @GetMapping("/api/ranking")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getRanking(
            @RequestParam(defaultValue = "guess") String mode,
            @RequestParam(defaultValue = "score") String type,
            @RequestParam(defaultValue = "all") String period,
            @RequestParam(defaultValue = "10") int limit) {

        List<Member> members;

        if ("multi".equals(mode)) {
            // 멀티게임 랭킹
            members = getMultiRankingMembers(type, period, limit);
            return ResponseEntity.ok(toMultiRankingResponse(members, period));
        } else if ("retro".equals(mode)) {
            // 레트로 게임 랭킹
            members = getRetroRankingMembers(type, period, limit);
            return ResponseEntity.ok(toRetroRankingResponse(members, period));
        } else {
            // 내가맞추기 랭킹 (기본값)
            members = getGuessRankingMembers(type, period, limit);
            return ResponseEntity.ok(toGuessRankingResponse(members, period));
        }
    }

    // 내가맞추기 랭킹 멤버 조회
    private List<Member> getGuessRankingMembers(String type, String period, int limit) {
        if ("weekly".equals(period)) {
            return memberService.getWeeklyGuessRankingByScore(limit);
        } else if ("best".equals(period)) {
            return memberService.getGuessBestScoreRanking(limit);
        } else {
            // all (전체) - 랭킹 타입
            switch (type) {
                case "accuracy":
                    return memberService.getGuessRankingByAccuracy(limit);
                case "avgScore":
                    return memberService.getGuessRankingByAvgScore(limit);
                case "avgScorePerRound":
                    return memberService.getGuessRankingByAvgScorePerRound(limit);
                case "accuracyMin10":
                    return memberService.getGuessRankingByAccuracyMin10(limit);
                case "correct":
                    return memberService.getGuessRankingByCorrect(limit);
                case "games":
                    return memberService.getGuessRankingByGames(limit);
                case "rounds":
                    return memberService.getGuessRankingByRounds(limit);
                case "score":
                default:
                    return memberService.getGuessRankingByScore(limit);
            }
        }
    }

    // 멀티게임 랭킹 멤버 조회
    private List<Member> getMultiRankingMembers(String type, String period, int limit) {
        if ("weekly".equals(period)) {
            return memberService.getWeeklyMultiRankingByScore(limit);
        } else if ("best".equals(period)) {
            return memberService.getMultiBestScoreRanking(limit);
        } else if ("tier".equals(period)) {
            // LP 티어 랭킹
            return memberService.getMultiTierRanking(limit);
        } else if ("wins".equals(period)) {
            // 1등 횟수 랭킹
            return memberService.getMultiWinsRanking(limit);
        } else {
            // all (전체)
            switch (type) {
                case "accuracy":
                    return memberService.getMultiRankingByAccuracy(limit);
                case "games":
                    return memberService.getMultiRankingByGames(limit);
                case "score":
                default:
                    return memberService.getMultiRankingByScore(limit);
            }
        }
    }

    // 레트로 게임 랭킹 멤버 조회
    private List<Member> getRetroRankingMembers(String type, String period, int limit) {
        if ("weekly".equals(period)) {
            return memberService.getWeeklyRetroRankingByScore(limit);
        } else if ("best30".equals(period)) {
            // 레트로 30곡 최고점 (역대)
            return memberService.getRetroBest30Ranking(limit);
        } else {
            // all (누적)
            switch (type) {
                case "accuracy":
                    return memberService.getRetroRankingByAccuracy(limit);
                case "games":
                    return memberService.getRetroRankingByGames(limit);
                case "score":
                default:
                    return memberService.getRetroRankingByScore(limit);
            }
        }
    }

    // 내가맞추기 랭킹 응답 변환
    private List<Map<String, Object>> toGuessRankingResponse(List<Member> members, String period) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Member member : members) {
            Map<String, Object> memberInfo = new HashMap<>();
            memberInfo.put("id", member.getId());
            memberInfo.put("nickname", member.getNickname());

            // 기간에 따른 점수 표시
            if ("weekly".equals(period)) {
                memberInfo.put("totalScore", member.getWeeklyGuessScore() != null ? member.getWeeklyGuessScore() : 0);
                memberInfo.put("totalGames", member.getWeeklyGuessGames() != null ? member.getWeeklyGuessGames() : 0);
                memberInfo.put("totalCorrect", member.getWeeklyGuessCorrect() != null ? member.getWeeklyGuessCorrect() : 0);
                memberInfo.put("totalRounds", member.getWeeklyGuessRounds() != null ? member.getWeeklyGuessRounds() : 0);
                memberInfo.put("accuracyRate", member.getWeeklyGuessAccuracyRate());
            } else if ("best".equals(period)) {
                memberInfo.put("totalScore", member.getBestGuessScore() != null ? member.getBestGuessScore() : 0);
                memberInfo.put("accuracyRate", member.getBestGuessAccuracy() != null ? member.getBestGuessAccuracy() : 0.0);
                memberInfo.put("achievedAt", member.getBestGuessAt());
                // 최고 기록 모드에서는 게임수/라운드수 없음
                memberInfo.put("totalGames", 1);
                memberInfo.put("totalCorrect", 0);
                memberInfo.put("totalRounds", 0);
            } else {
                memberInfo.put("totalScore", member.getGuessScore() != null ? member.getGuessScore() : 0);
                memberInfo.put("totalGames", member.getGuessGames() != null ? member.getGuessGames() : 0);
                memberInfo.put("totalCorrect", member.getGuessCorrect() != null ? member.getGuessCorrect() : 0);
                memberInfo.put("totalRounds", member.getGuessRounds() != null ? member.getGuessRounds() : 0);
                memberInfo.put("accuracyRate", member.getGuessAccuracyRate());
                memberInfo.put("averageScore", member.getGuessAverageScore());
                memberInfo.put("averageScorePerRound", member.getGuessAverageScorePerRound());
            }

            // 뱃지 정보 추가
            addBadgeInfo(memberInfo, member);

            result.add(memberInfo);
        }
        return result;
    }

    // 멀티게임 랭킹 응답 변환
    private List<Map<String, Object>> toMultiRankingResponse(List<Member> members, String period) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Member member : members) {
            Map<String, Object> memberInfo = new HashMap<>();
            memberInfo.put("id", member.getId());
            memberInfo.put("nickname", member.getNickname());

            // 기간에 따른 점수 표시
            if ("weekly".equals(period)) {
                memberInfo.put("totalScore", member.getWeeklyMultiScore() != null ? member.getWeeklyMultiScore() : 0);
                memberInfo.put("totalGames", member.getWeeklyMultiGames() != null ? member.getWeeklyMultiGames() : 0);
                memberInfo.put("totalCorrect", member.getWeeklyMultiCorrect() != null ? member.getWeeklyMultiCorrect() : 0);
                memberInfo.put("totalRounds", member.getWeeklyMultiRounds() != null ? member.getWeeklyMultiRounds() : 0);
                memberInfo.put("accuracyRate", member.getWeeklyMultiAccuracyRate());
            } else if ("best".equals(period)) {
                memberInfo.put("totalScore", member.getBestMultiScore() != null ? member.getBestMultiScore() : 0);
                memberInfo.put("accuracyRate", member.getBestMultiAccuracy() != null ? member.getBestMultiAccuracy() : 0.0);
                memberInfo.put("achievedAt", member.getBestMultiAt());
                // 최고 기록 모드에서는 게임수/라운드수 없음
                memberInfo.put("totalGames", 1);
                memberInfo.put("totalCorrect", 0);
                memberInfo.put("totalRounds", 0);
            } else if ("tier".equals(period)) {
                // LP 티어 랭킹
                memberInfo.put("multiLp", member.getMultiLp() != null ? member.getMultiLp() : 0);
                memberInfo.put("multiWins", member.getMultiWins() != null ? member.getMultiWins() : 0);
                memberInfo.put("multiTop3", member.getMultiTop3() != null ? member.getMultiTop3() : 0);
                memberInfo.put("totalGames", member.getMultiGames() != null ? member.getMultiGames() : 0);
                memberInfo.put("totalScore", 0);  // 티어 랭킹에서는 점수 대신 LP 표시
                memberInfo.put("accuracyRate", member.getMultiAccuracyRate());
            } else if ("wins".equals(period)) {
                // 1등 횟수 랭킹
                memberInfo.put("multiWins", member.getMultiWins() != null ? member.getMultiWins() : 0);
                memberInfo.put("totalGames", member.getMultiGames() != null ? member.getMultiGames() : 0);
                memberInfo.put("totalScore", member.getMultiWins() != null ? member.getMultiWins() : 0);  // 1등 횟수를 점수로 표시
                memberInfo.put("accuracyRate", member.getMultiAccuracyRate());
            } else {
                memberInfo.put("totalScore", member.getMultiScore() != null ? member.getMultiScore() : 0);
                memberInfo.put("totalGames", member.getMultiGames() != null ? member.getMultiGames() : 0);
                memberInfo.put("totalCorrect", member.getMultiCorrect() != null ? member.getMultiCorrect() : 0);
                memberInfo.put("totalRounds", member.getMultiRounds() != null ? member.getMultiRounds() : 0);
                memberInfo.put("accuracyRate", member.getMultiAccuracyRate());
                memberInfo.put("averageScore", member.getMultiAverageScore());
            }

            // 멀티 LP 티어 정보 추가
            addMultiTierInfo(memberInfo, member);

            // 뱃지 정보 추가
            addBadgeInfo(memberInfo, member);

            result.add(memberInfo);
        }
        return result;
    }

    // 레트로 게임 랭킹 응답 변환
    private List<Map<String, Object>> toRetroRankingResponse(List<Member> members, String period) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Member member : members) {
            Map<String, Object> memberInfo = new HashMap<>();
            memberInfo.put("id", member.getId());
            memberInfo.put("nickname", member.getNickname());

            // 기간에 따른 점수 표시
            if ("weekly".equals(period)) {
                memberInfo.put("totalScore", member.getWeeklyRetroScore() != null ? member.getWeeklyRetroScore() : 0);
                memberInfo.put("totalGames", member.getWeeklyRetroGames() != null ? member.getWeeklyRetroGames() : 0);
                memberInfo.put("totalCorrect", member.getWeeklyRetroCorrect() != null ? member.getWeeklyRetroCorrect() : 0);
                memberInfo.put("totalRounds", member.getWeeklyRetroRounds() != null ? member.getWeeklyRetroRounds() : 0);
                memberInfo.put("accuracyRate", member.getWeeklyRetroAccuracyRate());
            } else if ("best30".equals(period)) {
                memberInfo.put("totalScore", member.getRetroBest30Score() != null ? member.getRetroBest30Score() : 0);
                memberInfo.put("achievedAt", member.getRetroBest30At());
                memberInfo.put("totalGames", 1);
                memberInfo.put("accuracyRate", 0.0);
            } else {
                // all (누적)
                memberInfo.put("totalScore", member.getRetroScore() != null ? member.getRetroScore() : 0);
                memberInfo.put("totalGames", member.getRetroGames() != null ? member.getRetroGames() : 0);
                memberInfo.put("totalCorrect", member.getRetroCorrect() != null ? member.getRetroCorrect() : 0);
                memberInfo.put("totalRounds", member.getRetroRounds() != null ? member.getRetroRounds() : 0);
                memberInfo.put("accuracyRate", member.getRetroAccuracyRate());
            }

            // 뱃지 정보 추가
            addBadgeInfo(memberInfo, member);

            result.add(memberInfo);
        }
        return result;
    }

    // 뱃지 정보 추가
    private void addBadgeInfo(Map<String, Object> memberInfo, Member member) {
        if (member.getSelectedBadge() != null) {
            memberInfo.put("badgeEmoji", member.getSelectedBadgeEmoji());
            memberInfo.put("badgeName", member.getSelectedBadgeName());
        } else {
            memberInfo.put("badgeEmoji", null);
            memberInfo.put("badgeName", null);
        }
    }

    // 멀티게임 LP 티어 정보 추가
    private void addMultiTierInfo(Map<String, Object> memberInfo, Member member) {
        memberInfo.put("multiTier", member.getMultiTier() != null ? member.getMultiTier().name() : "BRONZE");
        memberInfo.put("multiTierDisplayName", member.getMultiTierDisplayName());
        memberInfo.put("multiTierColor", member.getMultiTierColor());
        memberInfo.put("multiLp", member.getMultiLp() != null ? member.getMultiLp() : 0);
    }

    // 30곡 최고점 랭킹 API (점수 → 소요시간 순)
    @GetMapping("/api/ranking/best30")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getBest30Ranking(
            @RequestParam(defaultValue = "weekly") String period,
            @RequestParam(defaultValue = "50") int limit) {

        List<Map<String, Object>> ranking;
        switch (period) {
            case "monthly":
                ranking = gameSessionService.getMonthlyBest30RankingByDuration(limit);
                break;
            case "alltime":
                ranking = gameSessionService.getAllTimeBest30RankingByDuration(limit);
                break;
            case "weekly":
            default:
                ranking = gameSessionService.getWeeklyBest30RankingByDuration(limit);
                break;
        }

        return ResponseEntity.ok(ranking);
    }

    // 내 30곡 순위 API (점수 → 소요시간 순)
    @GetMapping("/api/ranking/best30/my")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMyBest30Ranking(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        Map<String, Object> result = new HashMap<>();

        if (memberId == null) {
            result.put("loggedIn", false);
            return ResponseEntity.ok(result);
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member == null) {
            result.put("loggedIn", false);
            return ResponseEntity.ok(result);
        }

        result.put("loggedIn", true);
        result.put("nickname", member.getNickname());

        // 주간 30곡 순위 (GameSession 기반)
        Map<String, Object> weeklyRecord = gameSessionService.getMemberWeeklyBest30Record(memberId);
        if (weeklyRecord != null) {
            Long weeklyRank = gameSessionService.getMyWeeklyBest30Rank(memberId);
            Long weeklyTotal = gameSessionService.getWeeklyBest30ParticipantCount();
            result.put("weeklyRank", weeklyRank);
            result.put("weeklyTotal", weeklyTotal);
            result.put("weeklyScore", weeklyRecord.get("score"));
            result.put("weeklyDuration", weeklyRecord.get("durationFormatted"));
            result.put("weeklyDurationSeconds", weeklyRecord.get("durationSeconds"));
            result.put("weeklyAt", weeklyRecord.get("achievedAt"));
        } else {
            result.put("weeklyRank", 0);
            result.put("weeklyScore", 0);
        }

        // 월간 30곡 순위
        Map<String, Object> monthlyRecord = gameSessionService.getMemberMonthlyBest30Record(memberId);
        if (monthlyRecord != null) {
            Long monthlyRank = gameSessionService.getMyMonthlyBest30Rank(memberId);
            Long monthlyTotal = gameSessionService.getMonthlyBest30ParticipantCount();
            result.put("monthlyRank", monthlyRank);
            result.put("monthlyTotal", monthlyTotal);
            result.put("monthlyScore", monthlyRecord.get("score"));
            result.put("monthlyDuration", monthlyRecord.get("durationFormatted"));
            result.put("monthlyDurationSeconds", monthlyRecord.get("durationSeconds"));
            result.put("monthlyAt", monthlyRecord.get("achievedAt"));
        } else {
            result.put("monthlyRank", 0);
            result.put("monthlyScore", 0);
        }

        // 역대 30곡 순위
        Map<String, Object> allTimeRecord = gameSessionService.getMemberAllTimeBest30Record(memberId);
        if (allTimeRecord != null) {
            // 역대는 순위 쿼리가 없으므로 전체 랭킹에서 계산
            Long allTimeTotal = gameSessionService.getAllTimeBest30ParticipantCount();
            result.put("allTimeTotal", allTimeTotal);
            result.put("allTimeScore", allTimeRecord.get("score"));
            result.put("allTimeDuration", allTimeRecord.get("durationFormatted"));
            result.put("allTimeDurationSeconds", allTimeRecord.get("durationSeconds"));
            result.put("allTimeAt", allTimeRecord.get("achievedAt"));
        } else {
            result.put("allTimeRank", 0);
            result.put("allTimeScore", 0);
        }

        return ResponseEntity.ok(result);
    }

    // 내 순위 API
    @GetMapping("/api/ranking/my")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMyRanking(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long memberId = userDetails != null ? userDetails.getMemberId() : null;
        Map<String, Object> result = new HashMap<>();

        if (memberId == null) {
            result.put("loggedIn", false);
            return ResponseEntity.ok(result);
        }

        Member member = memberService.findById(memberId).orElse(null);
        if (member == null) {
            result.put("loggedIn", false);
            return ResponseEntity.ok(result);
        }

        result.put("loggedIn", true);
        result.put("nickname", member.getNickname());
        result.put("tierColor", member.getMultiTierColor());
        result.put("tierDisplayName", member.getMultiTierDisplayName());

        // 내가맞추기 순위
        int guessScore = member.getGuessScore() != null ? member.getGuessScore() : 0;
        int guessGames = member.getGuessGames() != null ? member.getGuessGames() : 0;

        if (guessGames > 0) {
            long rank = memberService.getMyGuessRank(guessScore);
            long total = memberService.getGuessParticipantCount();
            result.put("guessRank", rank);
            result.put("guessTotal", total);
            result.put("guessScore", guessScore);
            result.put("guessGames", guessGames);
        } else {
            result.put("guessRank", 0);
            result.put("guessTotal", 0);
            result.put("guessScore", 0);
            result.put("guessGames", 0);
        }

        return ResponseEntity.ok(result);
    }

    // 팬 챌린지 글로벌 랭킹 API
    @GetMapping("/api/ranking/fan-challenge")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getFanChallengeRanking(
            @RequestParam(defaultValue = "perfect") String type,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> result = new ArrayList<>();

        if ("perfect".equals(type)) {
            // 퍼펙트 클리어 횟수 랭킹
            List<Object[]> rankings = fanChallengeService.getPerfectClearRanking(limit);
            for (Object[] row : rankings) {
                Long memberId = (Long) row[0];
                Long perfectCount = (Long) row[1];

                Member member = memberService.findById(memberId).orElse(null);
                if (member != null) {
                    Map<String, Object> memberInfo = new HashMap<>();
                    memberInfo.put("id", member.getId());
                    memberInfo.put("nickname", member.getNickname());
                    memberInfo.put("perfectCount", perfectCount);
                    addBadgeInfo(memberInfo, member);
                    result.add(memberInfo);
                }
            }
        } else if ("artist".equals(type)) {
            // 도전 아티스트 수 랭킹
            List<Object[]> rankings = fanChallengeService.getArtistClearCountRanking(limit);
            for (Object[] row : rankings) {
                Long memberId = (Long) row[0];
                Long artistCount = (Long) row[1];

                Member member = memberService.findById(memberId).orElse(null);
                if (member != null) {
                    Map<String, Object> memberInfo = new HashMap<>();
                    memberInfo.put("id", member.getId());
                    memberInfo.put("nickname", member.getNickname());
                    memberInfo.put("artistCount", artistCount);
                    addBadgeInfo(memberInfo, member);
                    result.add(memberInfo);
                }
            }
        }

        return ResponseEntity.ok(result);
    }

    // 팬 챌린지 아티스트 + 단계별 랭킹 API
    @GetMapping("/api/ranking/fan-challenge/artist")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getFanChallengeArtistRanking(
            @RequestParam String artist,
            @RequestParam(defaultValue = "1") int stageLevel,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> result = new ArrayList<>();

        // 아티스트 + 단계별 랭킹 조회
        var records = fanChallengeService.getArtistStageRanking(artist, stageLevel, limit);

        for (var record : records) {
            Member member = record.getMember();
            Map<String, Object> memberInfo = new HashMap<>();
            memberInfo.put("id", member.getId());
            memberInfo.put("nickname", member.getNickname());
            memberInfo.put("correctCount", record.getCorrectCount());
            memberInfo.put("totalSongs", record.getTotalSongs());
            memberInfo.put("isPerfectClear", record.getIsPerfectClear());
            memberInfo.put("bestTimeMs", record.getBestTimeMs());
            memberInfo.put("bestTimeFormatted", record.getBestTimeMs() != null
                    ? String.format("%.1f초", record.getBestTimeMs() / 1000.0) : "-");
            memberInfo.put("achievedAt", record.getAchievedAt());
            memberInfo.put("stageLevel", record.getStageLevel());
            addBadgeInfo(memberInfo, member);
            result.add(memberInfo);
        }

        return ResponseEntity.ok(result);
    }

    // 장르 챌린지 글로벌 랭킹 API
    @GetMapping("/api/ranking/genre-challenge")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getGenreChallengeRanking(
            @RequestParam(defaultValue = "totalCorrect") String type,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> result = new ArrayList<>();

        if ("totalCorrect".equals(type)) {
            // 총 정답수 랭킹 (전체 장르 합산)
            List<Object[]> rankings = genreChallengeRecordRepository.findTotalCorrectCountRanking(PageRequest.of(0, limit));
            for (Object[] row : rankings) {
                Long memberId = (Long) row[0];
                Long totalCorrect = (Long) row[1];

                Member member = memberService.findById(memberId).orElse(null);
                if (member != null) {
                    Map<String, Object> memberInfo = new HashMap<>();
                    memberInfo.put("id", member.getId());
                    memberInfo.put("nickname", member.getNickname());
                    memberInfo.put("totalCorrect", totalCorrect);
                    addBadgeInfo(memberInfo, member);
                    result.add(memberInfo);
                }
            }
        } else if ("genreCount".equals(type)) {
            // 도전 장르 수 랭킹
            List<Object[]> rankings = genreChallengeRecordRepository.findGenreClearCountRanking(PageRequest.of(0, limit));
            for (Object[] row : rankings) {
                Long memberId = (Long) row[0];
                Long genreCount = (Long) row[1];

                Member member = memberService.findById(memberId).orElse(null);
                if (member != null) {
                    Map<String, Object> memberInfo = new HashMap<>();
                    memberInfo.put("id", member.getId());
                    memberInfo.put("nickname", member.getNickname());
                    memberInfo.put("genreCount", genreCount);
                    addBadgeInfo(memberInfo, member);
                    result.add(memberInfo);
                }
            }
        } else if ("maxCombo".equals(type)) {
            // 최대 콤보 랭킹
            List<Object[]> rankings = genreChallengeRecordRepository.findMaxComboRanking(PageRequest.of(0, limit));
            for (Object[] row : rankings) {
                Long memberId = (Long) row[0];
                Integer maxCombo = (Integer) row[1];

                Member member = memberService.findById(memberId).orElse(null);
                if (member != null) {
                    Map<String, Object> memberInfo = new HashMap<>();
                    memberInfo.put("id", member.getId());
                    memberInfo.put("nickname", member.getNickname());
                    memberInfo.put("maxCombo", maxCombo);
                    addBadgeInfo(memberInfo, member);
                    result.add(memberInfo);
                }
            }
        }

        return ResponseEntity.ok(result);
    }

    // 장르 챌린지용 장르 목록 API
    @GetMapping("/api/ranking/genre-challenge/genres")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getGenreChallengeGenres() {
        List<Map<String, Object>> result = new ArrayList<>();

        // 활성화된 장르 목록 조회
        genreRepository.findByUseYnOrderByDisplayOrderAsc("Y").forEach(genre -> {
            Map<String, Object> genreInfo = new HashMap<>();
            genreInfo.put("code", genre.getCode());
            genreInfo.put("name", genre.getName());
            result.add(genreInfo);
        });

        return ResponseEntity.ok(result);
    }

    // 장르별 챌린지 랭킹 API
    @GetMapping("/api/ranking/genre-challenge/by-genre")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getGenreChallengeRankingByGenre(
            @RequestParam String genreCode,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> result = new ArrayList<>();

        // 장르별 랭킹 조회 (정답수 DESC, 시간 ASC)
        List<GenreChallengeRecord> records = genreChallengeRecordRepository
                .findTopByGenreCode(genreCode, PageRequest.of(0, limit));

        for (GenreChallengeRecord record : records) {
            Member member = record.getMember();
            Map<String, Object> memberInfo = new HashMap<>();
            memberInfo.put("id", member.getId());
            memberInfo.put("nickname", member.getNickname());
            memberInfo.put("correctCount", record.getCorrectCount());
            memberInfo.put("totalSongs", Math.min(record.getTotalSongs(), GenreChallengeService.MAX_SONG_COUNT));
            memberInfo.put("maxCombo", record.getMaxCombo());
            memberInfo.put("achievedAt", record.getAchievedAt());
            addBadgeInfo(memberInfo, member);
            result.add(memberInfo);
        }

        return ResponseEntity.ok(result);
    }
}
