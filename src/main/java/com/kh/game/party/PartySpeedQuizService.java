package com.kh.game.party;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
    private final Random random = new Random();

    @Autowired
    public PartySpeedQuizService(PartyItemRepository partyItemRepository, PartySpeedHolder holder) {
        this(partyItemRepository, holder, Clock.systemDefaultZone());
    }

    PartySpeedQuizService(PartyItemRepository partyItemRepository, PartySpeedHolder holder, Clock clock) {
        this.partyItemRepository = partyItemRepository;
        this.holder = holder;
        this.clock = clock;
    }

    // ---------- 설정 ----------

    public synchronized void configure(String topic, int limitSecondsA, int limitSecondsB,
                                       PartySpeedMode mode, boolean wordOnBoard) {
        PartySpeedState state = holder.get();
        settle(state);
        if (isRunning(state)) {
            throw new PartyGameException("턴이 진행 중일 때는 설정을 바꿀 수 없습니다");
        }
        if (limitSecondsA <= 0 || limitSecondsB <= 0) {
            throw new PartyGameException("제한 시간은 1초 이상이어야 합니다");
        }
        String normalizedTopic = isAll(topic) ? null : topic;
        if (normalizedTopic != null && words().stream().noneMatch(w -> normalizedTopic.equals(w.getSubCategory()))) {
            throw new PartyGameException("주제를 알 수 없습니다: '" + topic + "'");
        }
        PartySpeedState.Setup setup = state.getSetup();
        setup.setTopic(normalizedTopic);
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
        settle(state);
        PartySpeedState.Turn turn = requireTurn(state);
        if (turn.isOver()) {
            throw new PartyGameException("턴이 끝났습니다");
        }
        PartySpeedState.TeamResult result = state.getResults().get(turn.getTeam());
        if (correct) {
            result.setCorrect(result.getCorrect() + 1);
        } else {
            result.setPass(result.getPass() + 1);
        }
        state.setLastAction(new PartySpeedState.LastAction(correct, state.getCurrentWord()));
        state.setCurrentWord(draw(state));
        if (state.getCurrentWord() == null) {
            endTurn(state);
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
        boolean allDone = true;
        for (PartyTeam team : PartyTeam.values()) {
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
                allDone ? firstPick(state) : null);
    }

    public synchronized PartySpeedConsoleView consoleView() {
        PartySpeedState state = holder.get();
        PartySpeedBoardView board = boardView();
        boolean running = "RUN".equals(board.phase());
        String word = running && state.getCurrentWord() != null ? state.getCurrentWord().getText() : null;

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
        return new PartySpeedConsoleView(board, word, state.getSetup(), topics, state.getLastAction() != null);
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

    /** 제한 시간(+여유)이 지난 턴을 끝난 것으로 확정한다. 콘솔이 턴 종료를 보내지 못했어도 진행이 막히지 않는다. */
    private void settle(PartySpeedState state) {
        PartySpeedState.Turn turn = state.getTurn();
        if (turn == null || turn.isOver()) {
            return;
        }
        Duration limit = Duration.ofSeconds(limitOf(state, turn.getTeam())).plus(GRACE);
        if (elapsed(turn).compareTo(limit) > 0) {
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

    /** 설정한 주제에서 아직 안 나온 제시어 하나. 없으면 null. */
    private PartySpeedState.Word draw(PartySpeedState state) {
        String topic = state.getSetup().getTopic();
        List<PartyItem> candidates = words().stream()
                .filter(item -> topic == null || topic.equals(item.getSubCategory()))
                .filter(item -> !state.getUsedItemIds().contains(item.getId()))
                .toList();
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
