package com.kh.game.party;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 스피드퀴즈 상태. 서버 전체에 1개(PartySpeedHolder)이고 그대로 JSON 스냅샷으로 저장된다.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartySpeedState {

    private long version;
    private Setup setup = new Setup();
    private Map<PartyTeam, TeamResult> results = emptyResults();
    private Set<Long> usedItemIds = new HashSet<>();

    /** 진행 중이거나 막 끝난 턴. 턴을 시작한 적이 없으면 null. */
    private Turn turn;
    private Word currentWord;
    /** 되돌릴 수 있는 직전 정답·패스. 되돌리면 비운다(연속 되돌리기 없음). */
    private LastAction lastAction;

    static Map<PartyTeam, TeamResult> emptyResults() {
        Map<PartyTeam, TeamResult> results = new EnumMap<>(PartyTeam.class);
        for (PartyTeam team : PartyTeam.values()) {
            results.put(team, new TeamResult());
        }
        return results;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Setup {
        /** null 이면 전체 주제. */
        private String topic;
        private Map<PartyTeam, Integer> limitSeconds = defaultLimits();
        private PartySpeedMode mode = PartySpeedMode.TALK;
        /** false 면 제시어를 콘솔에만 보인다. */
        private boolean wordOnBoard = true;

        private static Map<PartyTeam, Integer> defaultLimits() {
            Map<PartyTeam, Integer> limits = new EnumMap<>(PartyTeam.class);
            for (PartyTeam team : PartyTeam.values()) {
                limits.put(team, 90);
            }
            return limits;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamResult {
        private int correct;
        private int pass;
        private boolean done;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Turn {
        private PartyTeam team;
        private LocalDateTime startedAt;
        /** 시간 종료·턴 종료·제시어 소진으로 끝났다. */
        private boolean over;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Word {
        private Long itemId;
        private String text;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LastAction {
        private boolean correct;
        private Word word;
    }
}
