package com.kh.game.party;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 본게임 상태. 서버 전체에 1개(PartyGameHolder)이고 그대로 JSON 스냅샷으로 저장된다.
 * 현재 문제는 DB 행이 아니라 사본을 들고 있어 재시작 뒤에도 DB 없이 이어진다.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartyGameState implements PartyVersioned {

    private long version;
    private PartyPhase phase = PartyPhase.WAIT;
    private int round;
    private Map<PartyTeam, String> teamNames = defaultTeamNames();
    private Map<PartyTeam, Integer> scores = zeroScores();

    private Question question;
    private int hintsOpened;
    private PartyTeam wrongTeam;
    /** 이번 문제에서 오답을 낸 팀들. 두 팀이 다 있으면 자유 도전. */
    private Set<PartyTeam> wrongTeams = new HashSet<>();
    private PartyTeam scoringTeam;
    private LocalDateTime timerStartedAt;

    private long playerSeq;
    private String playerCmd;

    private Set<Long> usedItemIds = new HashSet<>();
    private Set<Long> usedSongIds = new HashSet<>();
    private List<HistoryEntry> history = new ArrayList<>();

    private static Map<PartyTeam, String> defaultTeamNames() {
        Map<PartyTeam, String> names = new EnumMap<>(PartyTeam.class);
        names.put(PartyTeam.A, "남팀");
        names.put(PartyTeam.B, "여팀");
        return names;
    }

    private static Map<PartyTeam, Integer> zeroScores() {
        Map<PartyTeam, Integer> scores = new EnumMap<>(PartyTeam.class);
        scores.put(PartyTeam.A, 0);
        scores.put(PartyTeam.B, 0);
        return scores;
    }

    /** 뽑힌 문제의 사본. itemId·songId 중 하나만 있다. */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Question {
        private Long itemId;
        private Long songId;
        private String category;
        private String subCategory;
        private PartyPresentation presentation;
        private String answer;
        private String aliases;
        private String detail;
        private String source;
        private String videoId;
        private Integer startTime;
        private Integer duration;
        private String imagePath;
        private String questionText;
        private Integer difficulty;
        private List<String> hints = new ArrayList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryEntry {
        private int round;
        private String category;
        private String subCategory;
        private String answer;
        private PartyTeam scoringTeam;
    }
}
