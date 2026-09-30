package com.kh.game.party;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 스피드퀴즈(몸풀기) 규칙. 본게임 첫 대분류 선택권을 정한다 — 본게임 점수와는 무관하다.
 * 타이머는 콘솔 화면이 세지만 판정은 서버 시계로 한다(시작 시각을 들고 있어 재시작 뒤에도 이어진다).
 */
@Slf4j
@Service
public class PartySpeedQuizService {

    public static final String ALL = "전체";
    /** 0초 직전에 누른 요청이 시계 차이·전송 지연으로 거부되지 않게 두는 여유. */
    private static final Duration GRACE = Duration.ofSeconds(1);

    private final PartyItemRepository partyItemRepository;
    private final PartySpeedHolder holder;
    private final Clock clock;
    /**
     * 정답·패스 사이의 최소 간격. 사람의 더블클릭(0.1~0.3초)은 두 번째 클릭 때 화면이 이미 새 버전을 들고 있어
     * 버전 확인으로는 못 거른다. 제시어 하나를 이 시간 안에 설명하고 맞히는 일은 없으므로 서버가 거른다.
     */
    private final Duration minAnswerInterval;
    private final Random random = new Random();
    private Instant lastAnswerAt;

    @Autowired
    public PartySpeedQuizService(PartyItemRepository partyItemRepository, PartySpeedHolder holder,
                                 @Value("${party.speed-min-answer-interval-ms:300}") long minAnswerIntervalMs) {
        this(partyItemRepository, holder, Clock.systemDefaultZone(), Duration.ofMillis(minAnswerIntervalMs));
    }

    PartySpeedQuizService(PartyItemRepository partyItemRepository, PartySpeedHolder holder, Clock clock,
                          Duration minAnswerInterval) {
        this.partyItemRepository = partyItemRepository;
        this.holder = holder;
        this.clock = clock;
        this.minAnswerInterval = minAnswerInterval;
    }

    /**
     * 콘솔의 조작 하나를 적용한다. 화면이 본 버전이 지금과 다르면 적용하지 않는다 —
     * 빠르게 두 번 누른 [정답]·[패스] 가 두 번 세지지 않게 한다.
     */
    public synchronized PartySpeedConsoleView apply(long expectedVersion, Runnable action) {
        if (holder.get().getVersion() != expectedVersion) {
            throw new PartyStaleException();
        }
        action.run();
        return consoleView();
    }

    // ---------- 설정 ----------

    /** topics 가 비어 있으면(또는 '전체' 만 있으면) 전체 주제. */
    public synchronized void configure(Collection<String> topics, int limitSecondsA, int limitSecondsB,
                                       PartySpeedMode mode, boolean wordOnBoard) {
        PartySpeedState state = holder.get();
        settle(state);
        if (isRunning(state)) {
            throw new PartyGameException("턴이 진행 중일 때는 설정을 바꿀 수 없습니다");
        }
        if (limitSecondsA <= 0 || limitSecondsB <= 0) {
            throw new PartyInputException("제한 시간은 1초 이상이어야 합니다");
        }
        Set<String> chosen = new LinkedHashSet<>();
        if (topics != null) {
            topics.stream().filter(topic -> !isAll(topic)).forEach(chosen::add);
        }
        Set<String> known = words().stream().map(PartyItem::getSubCategory).collect(Collectors.toSet());
        for (String topic : chosen) {
            if (!known.contains(topic)) {
                throw new PartyInputException("주제를 알 수 없습니다: '" + topic + "'");
            }
        }
        PartySpeedState.Setup setup = state.getSetup();
        setup.setTopics(chosen);
        setup.getLimitSeconds().put(PartyTeam.A, limitSecondsA);
        setup.getLimitSeconds().put(PartyTeam.B, limitSecondsB);
        setup.setMode(mode);
        setup.setWordOnBoard(wordOnBoard);
        commit(state);
    }

    // ---------- 턴 ----------

    public synchronized void start(PartyTeam team) {
        PartySpeedState state = holder.get();
        settle(state);
        if (isRunning(state)) {
            throw new PartyGameException("턴이 진행 중입니다");
        }
        if (state.getResults().get(team).isDone()) {
            throw new PartyGameException("이미 턴을 마친 팀입니다. 다시 하려면 재대결을 누르세요");
        }
        PartySpeedState.Word word = draw(state);
        if (word == null) {
            throw new PartyGameException("남은 제시어가 없습니다");
        }
        state.setTurn(new PartySpeedState.Turn(team, LocalDateTime.now(clock), false));
        state.setCurrentWord(word);
        state.setLastAction(null);
        state.getTurnLog().clear();
        lastAnswerAt = null;
        commit(state);
    }

    public synchronized void correct() {
        answer(true);
    }

    public synchronized void pass() {
        answer(false);
    }

    private void answer(boolean correct) {
        PartySpeedState state = holder.get();
        PartySpeedState.Turn turn = requireTurn(state);
        // 정답·패스만 여유를 둔다: 0초 직전에 누른 요청이 늦게 도착해도 받는다
        boolean timeUp = !turn.isOver() && isTimeUp(state, turn);
        if (timeUp && elapsed(turn).compareTo(limitDuration(state, turn).plus(GRACE)) > 0) {
            endTurn(state);
            commit(state);
        }
        if (turn.isOver()) {
            throw new PartyGameException("턴이 끝났습니다");
        }
        Instant now = clock.instant();
        if (lastAnswerAt != null && Duration.between(lastAnswerAt, now).compareTo(minAnswerInterval) < 0) {
            throw new PartyGameException("방금 누른 것과 너무 가깝습니다 — 한 번만 적용했습니다");
        }
        lastAnswerAt = now;

        PartySpeedState.TeamResult result = state.getResults().get(turn.getTeam());
        if (correct) {
            result.setCorrect(result.getCorrect() + 1);
        } else {
            result.setPass(result.getPass() + 1);
        }
        state.setLastAction(new PartySpeedState.LastAction(correct, state.getCurrentWord()));
        state.getTurnLog().add(state.getLastAction());
        if (timeUp) {
            // 시간은 이미 끝났다 — 아무도 볼 수 없는 새 제시어를 뽑아 소모하지 않는다
            endTurn(state);
        } else {
            state.setCurrentWord(draw(state));
            if (state.getCurrentWord() == null) {
                endTurn(state);
            }
        }
        commit(state);
    }

    /** 직전 정답·패스 1번을 취소한다. 턴이 끝난 뒤에는 수만 줄이고 제시어는 다시 띄우지 않는다. */
    public synchronized void undo() {
        PartySpeedState state = holder.get();
        settle(state);
        PartySpeedState.Turn turn = requireTurn(state);
        PartySpeedState.LastAction last = state.getLastAction();
        if (last == null) {
            throw new PartyGameException("되돌릴 것이 없습니다");
        }
        PartySpeedState.TeamResult result = state.getResults().get(turn.getTeam());
        if (last.isCorrect()) {
            result.setCorrect(result.getCorrect() - 1);
        } else {
            result.setPass(result.getPass() - 1);
        }
        if (!turn.isOver()) {
            // 뒤에 뽑혔던 제시어는 아직 풀지 않았으니 다시 나올 수 있게 돌려놓는다
            state.getUsedItemIds().remove(state.getCurrentWord().getItemId());
            state.setCurrentWord(last.getWord());
        }
        state.setLastAction(null);
        if (!state.getTurnLog().isEmpty()) {
            state.getTurnLog().remove(state.getTurnLog().size() - 1);
        }
        lastAnswerAt = null;
        commit(state);
    }

    public synchronized void finish() {
        PartySpeedState state = holder.get();
        settle(state);
        PartySpeedState.Turn turn = requireTurn(state);
        if (!turn.isOver()) {
            endTurn(state);
            commit(state);
        }
    }

    /** 결과만 비운다. 설정과 나온 제시어 기록은 남는다(동점 재대결에서 같은 제시어가 다시 나오지 않게). */
    public synchronized void rematch() {
        PartySpeedState state = holder.get();
        settle(state);
        if (isRunning(state)) {
            throw new PartyGameException("턴이 진행 중입니다");
        }
        state.setResults(PartySpeedState.emptyResults());
        state.setTurn(null);
        state.setCurrentWord(null);
        state.setLastAction(null);
        state.getTurnLog().clear();
        commit(state);
    }

    public synchronized void reset() {
        holder.reset();
    }

    // ---------- 조회 ----------

    public synchronized PartySpeedBoardView boardView() {
        PartySpeedState state = holder.get();
        PartySpeedState.Turn turn = state.getTurn();
        Integer limit = turn == null ? null : limitOf(state, turn.getTeam());
        Integer remaining = turn == null ? null
                : (int) Math.max(0, limit - elapsed(turn).toSeconds());
        boolean over = turn != null && (turn.isOver() || remaining == 0);

        Map<String, PartySpeedBoardView.TeamResult> results = new LinkedHashMap<>();
        Map<String, Integer> limits = new LinkedHashMap<>();
        boolean allDone = true;
        for (PartyTeam team : PartyTeam.values()) {
            limits.put(team.name(), limitOf(state, team));
            PartySpeedState.TeamResult result = state.getResults().get(team);
            boolean done = result.isDone() || (over && turn.getTeam() == team);
            results.put(team.name(), new PartySpeedBoardView.TeamResult(result.getCorrect(), result.getPass(), done));
            allDone &= done;
        }

        String phase = turn == null ? "WAIT" : allDone ? "RESULT" : over ? "OVER" : "RUN";
        String word = turn != null && !over && state.getSetup().isWordOnBoard() && state.getCurrentWord() != null
                ? state.getCurrentWord().getText() : null;
        return new PartySpeedBoardView(state.getVersion(), phase, state.getSetup().getMode().name(),
                turn == null ? null : turn.getTeam().name(), limit, over ? Integer.valueOf(0) : remaining,
                turn == null ? null : turn.getStartedAt(), word, results,
                allDone ? firstPick(state) : null, limits);
    }

    public synchronized PartySpeedConsoleView consoleView() {
        PartySpeedState state = holder.get();
        PartySpeedBoardView board = boardView();
        boolean running = "RUN".equals(board.phase());
        String word = running && state.getCurrentWord() != null ? state.getCurrentWord().getText() : null;
        List<PartySpeedConsoleView.LogEntry> log = state.getTurnLog().stream()
                .map(entry -> new PartySpeedConsoleView.LogEntry(entry.getWord().getText(), entry.isCorrect()))
                .toList();
        return new PartySpeedConsoleView(board, word, state.getSetup(), state.getLastAction() != null, log);
    }

    /** 주제 → 남은 제시어 수. '전체' 가 있다. */
    public synchronized Map<String, Integer> topics() {
        PartySpeedState state = holder.get();
        Map<String, Integer> topics = new LinkedHashMap<>();
        topics.put(ALL, 0);
        for (PartyItem item : words()) {
            if (state.getUsedItemIds().contains(item.getId())) {
                continue;
            }
            topics.merge(ALL, 1, Integer::sum);
            if (item.getSubCategory() != null) {
                topics.merge(item.getSubCategory(), 1, Integer::sum);
            }
        }
        return topics;
    }

    // ---------- 내부 ----------

    private static boolean isAll(String topic) {
        return topic == null || topic.isBlank() || ALL.equals(topic);
    }

    private List<PartyItem> words() {
        return partyItemRepository.findByCategoryAndUseYn(PartyCategory.SPEED, "Y");
    }

    private int limitOf(PartySpeedState state, PartyTeam team) {
        return state.getSetup().getLimitSeconds().get(team);
    }

    private Duration elapsed(PartySpeedState.Turn turn) {
        return Duration.between(turn.getStartedAt(), LocalDateTime.now(clock));
    }

    private boolean isRunning(PartySpeedState state) {
        return state.getTurn() != null && !state.getTurn().isOver();
    }

    private PartySpeedState.Turn requireTurn(PartySpeedState state) {
        if (state.getTurn() == null) {
            throw new PartyGameException("턴을 시작하세요");
        }
        return state.getTurn();
    }

    private Duration limitDuration(PartySpeedState state, PartySpeedState.Turn turn) {
        return Duration.ofSeconds(limitOf(state, turn.getTeam()));
    }

    /** 제한 시간이 됐다. 화면이 "끝남"으로 보이는 시점과 같다. */
    private boolean isTimeUp(PartySpeedState state, PartySpeedState.Turn turn) {
        return elapsed(turn).compareTo(limitDuration(state, turn)) >= 0;
    }

    /**
     * 제한 시간이 된 턴을 끝난 것으로 확정한다. 콘솔이 턴 종료를 보내지 못했어도 진행이 막히지 않는다.
     * 여유 1초는 정답·패스에만 있다 — 화면이 "끝남"을 보이는 순간부터 상대 팀 시작·설정·재대결이 된다.
     */
    private void settle(PartySpeedState state) {
        PartySpeedState.Turn turn = state.getTurn();
        if (turn == null || turn.isOver()) {
            return;
        }
        if (isTimeUp(state, turn)) {
            endTurn(state);
            commit(state);
        }
    }

    private void endTurn(PartySpeedState state) {
        PartySpeedState.Turn turn = state.getTurn();
        turn.setOver(true);
        PartySpeedState.TeamResult result = state.getResults().get(turn.getTeam());
        result.setDone(true);
        log.info("Party speed turn ended: team={} correct={} pass={}", turn.getTeam(),
                result.getCorrect(), result.getPass());
    }

    /**
     * 고른 주제들에서 아직 안 나온 제시어 하나. 고른 주제가 다 떨어지면 다른 주제에서 뽑는다 —
     * 턴이 중간에 끊기거나 뒤 팀이 시작도 못 하는 것보다 낫다. 두 팀이 같은 조건으로 붙으려면
     * 두 턴을 채울 만큼 주제를 여러 개 고른다(주제 하나는 10개 안팎이다).
     * 전부 떨어졌으면 null.
     */
    private PartySpeedState.Word draw(PartySpeedState state) {
        Set<String> topics = state.getSetup().getTopics();
        List<PartyItem> unused = words().stream()
                .filter(item -> !state.getUsedItemIds().contains(item.getId()))
                .toList();
        List<PartyItem> inTopic = unused.stream()
                .filter(item -> topics.isEmpty() || topics.contains(item.getSubCategory()))
                .toList();
        List<PartyItem> candidates = inTopic.isEmpty() ? unused : inTopic;
        if (candidates.isEmpty()) {
            return null;
        }
        PartyItem item = candidates.get(random.nextInt(candidates.size()));
        state.getUsedItemIds().add(item.getId());
        return new PartySpeedState.Word(item.getId(), item.getAnswer());
    }

    /** 정답 수가 많은 팀. 동점이면 null(MC 재량). */
    private String firstPick(PartySpeedState state) {
        int a = state.getResults().get(PartyTeam.A).getCorrect();
        int b = state.getResults().get(PartyTeam.B).getCorrect();
        return a == b ? null : (a > b ? PartyTeam.A : PartyTeam.B).name();
    }

    private void commit(PartySpeedState state) {
        state.setVersion(state.getVersion() + 1);
        holder.save();
    }
}
