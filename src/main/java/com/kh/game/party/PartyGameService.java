package com.kh.game.party;

import com.kh.game.entity.Song;
import com.kh.game.entity.SongAnswer;
import com.kh.game.repository.SongAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 파티 본게임 규칙. 상태는 서버 전체에 1개(PartyGameHolder)이고 콘솔 하나만 바꾼다.
 * 정답 텍스트 판정은 없다 — MC 가 [팀 정답]/[못 맞힘] 을 누른다.
 * 기존 게임 기록(GameSession)·통계·랭킹은 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyGameService {

    public static final String SONG = "SONG";
    public static final String ALL = "전체";
    private static final String IMAGE_URL_PREFIX = "/admin/party/images/";

    private final PartyItemRepository partyItemRepository;
    private final PartySongRepository partySongRepository;
    private final SongAnswerRepository songAnswerRepository;
    private final PartyGameHolder holder;
    private final Random random = new Random();

    // ---------- 출제 ----------

    public synchronized void pick(PartyCategory category, String subCategory) {
        if (category == PartyCategory.SPEED) {
            throw new PartyGameException("스피드퀴즈 제시어는 본게임에서 낼 수 없습니다");
        }
        PartyGameState state = holder.get();
        requirePickable(state);
        releaseUnshown(state);

        List<PartyItem> candidates = partyItemRepository.findPlayable().stream()
                .filter(item -> item.getCategory() == category)
                .filter(item -> isAll(subCategory) || subCategory.equals(item.getSubCategory()))
                .filter(item -> !state.getUsedItemIds().contains(item.getId()))
                .toList();
        if (candidates.isEmpty()) {
            throw new PartyGameException("남은 문제가 없습니다");
        }
        PartyItem item = candidates.get(random.nextInt(candidates.size()));

        state.getUsedItemIds().add(item.getId());
        ready(state, toQuestion(item));
    }

    public synchronized void pickSong(String bandLabel) {
        PartySongBand band = isAll(bandLabel) ? null : PartySongBand.ofLabel(bandLabel);
        PartyGameState state = holder.get();
        requirePickable(state);
        releaseUnshown(state);

        List<Long> candidates = partySongRepository.findPlayable().stream()
                .filter(song -> band == null || band.contains(song.getReleaseYear()))
                .map(PartySongRepository.SongYear::getId)
                .filter(id -> !state.getUsedSongIds().contains(id))
                .toList();
        if (candidates.isEmpty()) {
            throw new PartyGameException("남은 문제가 없습니다");
        }
        Long songId = candidates.get(random.nextInt(candidates.size()));
        Song song = partySongRepository.findById(songId).orElseThrow();

        state.getUsedSongIds().add(songId);
        ready(state, toQuestion(song));
    }

    /** 대분류 → 중분류 → 남은 문제 수. 각 대분류에 '전체' 가 있다. */
    public synchronized Map<String, Map<String, Integer>> remaining() {
        PartyGameState state = holder.get();
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();

        List<PartySongRepository.SongYear> songs = partySongRepository.findPlayable().stream()
                .filter(song -> !state.getUsedSongIds().contains(song.getId()))
                .toList();
        Map<String, Integer> songCounts = new LinkedHashMap<>();
        songCounts.put(ALL, songs.size());
        for (PartySongBand band : PartySongBand.all()) {
            songCounts.put(band.label(),
                    (int) songs.stream().filter(song -> band.contains(song.getReleaseYear())).count());
        }
        result.put(SONG, songCounts);

        for (PartyCategory category : PartyCategory.values()) {
            if (category != PartyCategory.SPEED) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                counts.put(ALL, 0);
                result.put(category.name(), counts);
            }
        }
        for (PartyItem item : partyItemRepository.findPlayable()) {
            if (state.getUsedItemIds().contains(item.getId())) {
                continue;
            }
            Map<String, Integer> counts = result.get(item.getCategory().name());
            counts.merge(ALL, 1, Integer::sum);
            if (item.getSubCategory() != null) {
                counts.merge(item.getSubCategory(), 1, Integer::sum);
            }
        }
        return result;
    }

    public synchronized void show() {
        PartyGameState state = holder.get();
        if (state.getPhase() != PartyPhase.READY) {
            throw new PartyGameException("먼저 문제를 뽑으세요");
        }
        state.setPhase(PartyPhase.SHOW);
        state.setRound(state.getRound() + 1);
        state.setTimerStartedAt(LocalDateTime.now());
        commit(state);
    }

    // ---------- 판정·점수 ----------

    public synchronized void correct(PartyTeam team) {
        PartyGameState state = requireShowing();
        state.getScores().merge(team, 1, Integer::sum);
        reveal(state, team);
    }

    public synchronized void miss() {
        reveal(requireShowing(), null);
    }

    /** 보드 표시만 바꾼다. 점수·이력에 남지 않는다. */
    public synchronized void wrong(PartyTeam team) {
        PartyGameState state = requireShowing();
        state.setWrongTeam(team);
        commit(state);
    }

    public synchronized void adjustScore(PartyTeam team, int delta) {
        PartyGameState state = holder.get();
        state.getScores().merge(team, delta, (current, change) -> Math.max(0, current + change));
        commit(state);
    }

    // ---------- 힌트·재생 ----------

    public synchronized void openHint() {
        PartyGameState state = requireShowing();
        PartyGameState.Question question = state.getQuestion();
        if (!PartyCategory.GAME.name().equals(question.getCategory())) {
            throw new PartyGameException("힌트는 GAME 문제에만 있습니다");
        }
        if (state.getHintsOpened() >= question.getHints().size()) {
            throw new PartyGameException("더 열 힌트가 없습니다");
        }
        state.setHintsOpened(state.getHintsOpened() + 1);
        commit(state);
    }

    public synchronized void play() {
        command("PLAY");
    }

    public synchronized void pause() {
        command("PAUSE");
    }

    public synchronized void restart() {
        command("RESTART");
    }

    private void command(String cmd) {
        PartyGameState state = holder.get();
        if (state.getPhase() != PartyPhase.SHOW && state.getPhase() != PartyPhase.REVEAL) {
            throw new PartyGameException("문제가 떠 있지 않습니다");
        }
        if (state.getQuestion().getPresentation() != PartyPresentation.AUDIO) {
            throw new PartyGameException("재생할 것이 없는 문제입니다");
        }
        state.setPlayerSeq(state.getPlayerSeq() + 1);
        state.setPlayerCmd(cmd);
        commit(state);
    }

    // ---------- 단계 전환 ----------

    public synchronized void next() {
        PartyGameState state = holder.get();
        if (state.getPhase() != PartyPhase.REVEAL) {
            throw new PartyGameException("정답 공개 뒤에만 다음으로 넘어갑니다");
        }
        clearQuestion(state);
        state.setPhase(PartyPhase.WAIT);
        commit(state);
    }

    public synchronized void showScores() {
        PartyGameState state = holder.get();
        if (state.getPhase() == PartyPhase.SHOW || state.getPhase() == PartyPhase.END) {
            throw new PartyGameException(state.getPhase() == PartyPhase.SHOW
                    ? "판정을 먼저 끝내세요" : "게임이 끝났습니다");
        }
        releaseUnshown(state);
        clearQuestion(state);
        state.setPhase(PartyPhase.SCORES);
        commit(state);
    }

    public synchronized void backToWait() {
        PartyGameState state = holder.get();
        if (state.getPhase() != PartyPhase.SCORES) {
            throw new PartyGameException("점수판이 떠 있지 않습니다");
        }
        state.setPhase(PartyPhase.WAIT);
        commit(state);
    }

    public synchronized void end() {
        PartyGameState state = holder.get();
        if (state.getPhase() == PartyPhase.SHOW) {
            throw new PartyGameException("판정을 먼저 끝내세요");
        }
        releaseUnshown(state);
        clearQuestion(state);
        state.setPhase(PartyPhase.END);
        commit(state);
        log.info("Party game ended: rounds={} scoreA={} scoreB={}", state.getRound(),
                state.getScores().get(PartyTeam.A), state.getScores().get(PartyTeam.B));
    }

    public synchronized void newGame() {
        holder.reset();
    }

    // ---------- 조회 ----------

    public synchronized PartyBoardView boardView() {
        PartyGameState state = holder.get();
        PartyPhase phase = state.getPhase();
        PartyGameState.Question question = state.getQuestion();
        boolean onBoard = question != null && (phase == PartyPhase.SHOW || phase == PartyPhase.REVEAL);

        PartyBoardView.Item item = null;
        List<String> hints = List.of();
        PartyBoardView.Reveal reveal = null;
        if (onBoard) {
            item = new PartyBoardView.Item(question.getCategory(), question.getSubCategory(),
                    question.getPresentation().name(), question.getVideoId(), question.getStartTime(),
                    question.getDuration(),
                    question.getImagePath() == null ? null : IMAGE_URL_PREFIX + question.getImagePath(),
                    question.getQuestionText());
            hints = List.copyOf(question.getHints().subList(0, state.getHintsOpened()));
        }
        if (onBoard && phase == PartyPhase.REVEAL) {
            reveal = new PartyBoardView.Reveal(question.getAnswer(), question.getDetail(), question.getSource(),
                    name(state.getScoringTeam()));
        }

        Map<String, String> teamNames = new LinkedHashMap<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (PartyTeam team : PartyTeam.values()) {
            teamNames.put(team.name(), state.getTeamNames().get(team));
            scores.put(team.name(), state.getScores().get(team));
        }
        return new PartyBoardView(state.getVersion(),
                (phase == PartyPhase.READY ? PartyPhase.WAIT : phase).name(),
                state.getRound(), teamNames, scores, item, hints,
                onBoard ? name(state.getWrongTeam()) : null, reveal,
                new PartyBoardView.Player(state.getPlayerSeq(), onBoard ? state.getPlayerCmd() : null),
                onBoard ? state.getTimerStartedAt() : null);
    }

    public synchronized PartyConsoleView consoleView() {
        PartyGameState state = holder.get();
        PartyGameState.Question question = state.getQuestion();
        PartyConsoleView.Card card = question == null ? null
                : new PartyConsoleView.Card(question.getCategory(), question.getSubCategory(),
                question.getPresentation().name(), question.getAnswer(), question.getAliases(),
                question.getDetail(), question.getSource(), List.copyOf(question.getHints()),
                state.getHintsOpened());
        return new PartyConsoleView(state.getPhase().name(), boardView(), card, remaining(),
                List.copyOf(state.getHistory()));
    }

    // ---------- 내부 ----------

    private static boolean isAll(String subCategory) {
        return subCategory == null || subCategory.isBlank() || ALL.equals(subCategory);
    }

    private static String name(PartyTeam team) {
        return team == null ? null : team.name();
    }

    private void requirePickable(PartyGameState state) {
        if (state.getPhase() == PartyPhase.SHOW) {
            throw new PartyGameException("판정을 먼저 끝내세요");
        }
        if (state.getPhase() == PartyPhase.END) {
            throw new PartyGameException("게임이 끝났습니다. 새 게임을 시작하세요");
        }
    }

    private PartyGameState requireShowing() {
        PartyGameState state = holder.get();
        if (state.getPhase() != PartyPhase.SHOW) {
            throw new PartyGameException("문제가 떠 있지 않습니다");
        }
        return state;
    }

    /** 뽑기만 하고 띄우지 않은 문제는 다시 나올 수 있게 돌려놓는다. */
    private void releaseUnshown(PartyGameState state) {
        PartyGameState.Question question = state.getQuestion();
        if (state.getPhase() != PartyPhase.READY || question == null) {
            return;
        }
        if (question.getItemId() != null) {
            state.getUsedItemIds().remove(question.getItemId());
        }
        if (question.getSongId() != null) {
            state.getUsedSongIds().remove(question.getSongId());
        }
    }

    private void ready(PartyGameState state, PartyGameState.Question question) {
        clearQuestion(state);
        state.setQuestion(question);
        state.setPhase(PartyPhase.READY);
        commit(state);
    }

    private void clearQuestion(PartyGameState state) {
        state.setQuestion(null);
        state.setHintsOpened(0);
        state.setWrongTeam(null);
        state.setScoringTeam(null);
        state.setTimerStartedAt(null);
        state.setPlayerCmd(null);
    }

    private void reveal(PartyGameState state, PartyTeam scoringTeam) {
        PartyGameState.Question question = state.getQuestion();
        state.setScoringTeam(scoringTeam);
        state.setPhase(PartyPhase.REVEAL);
        state.getHistory().add(new PartyGameState.HistoryEntry(state.getRound(), question.getCategory(),
                question.getSubCategory(), question.getAnswer(), scoringTeam));
        commit(state);
    }

    private void commit(PartyGameState state) {
        state.setVersion(state.getVersion() + 1);
        holder.save();
    }

    private PartyGameState.Question toQuestion(PartyItem item) {
        PartyGameState.Question question = new PartyGameState.Question();
        question.setItemId(item.getId());
        question.setCategory(item.getCategory().name());
        question.setSubCategory(item.getSubCategory());
        question.setPresentation(item.getPresentation());
        question.setAnswer(item.getAnswer());
        question.setAliases(item.getAnswerAliases());
        question.setDetail(item.getDetail());
        question.setSource(item.getSourceNote());
        question.setVideoId(item.getYoutubeVideoId());
        question.setStartTime(item.getStartTime());
        question.setDuration(item.getPlayDuration());
        question.setImagePath(item.getImagePath());
        question.setQuestionText(item.getQuestionText());
        question.setHints(new ArrayList<>(Stream.of(item.getHint1(), item.getHint2(), item.getHint3())
                .filter(Objects::nonNull).toList()));
        return question;
    }

    private PartyGameState.Question toQuestion(Song song) {
        PartyGameState.Question question = new PartyGameState.Question();
        question.setSongId(song.getId());
        question.setCategory(SONG);
        question.setSubCategory(song.getReleaseYear() == null ? ALL : PartySongBand.of(song.getReleaseYear()).label());
        question.setPresentation(PartyPresentation.AUDIO);
        question.setAnswer(song.getTitle());
        question.setAliases(songAnswerRepository.findBySongId(song.getId()).stream()
                .map(SongAnswer::getAnswer)
                .filter(answer -> !answer.equals(song.getTitle()))
                .distinct()
                .collect(Collectors.joining(", ")));
        question.setDetail(song.getReleaseYear() == null ? song.getArtist()
                : song.getArtist() + " · " + song.getReleaseYear());
        question.setVideoId(song.getYoutubeVideoId());
        question.setStartTime(song.getStartTime());
        question.setDuration(song.getPlayDuration());
        return question;
    }
}
