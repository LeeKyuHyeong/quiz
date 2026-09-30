package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스피드퀴즈 규칙: 설정 · 턴 · 되돌리기 · 결과 · 보드 노출 · 복구.
 * 시간은 손으로 돌리는 시계로 잰다(기계 속도와 무관).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("파티 스피드퀴즈 규칙")
class PartySpeedQuizServiceTest {

    @Autowired
    private PartyItemRepository partyItemRepository;
    @Autowired
    private PartyGameService gameService;
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    private final TestClock clock = new TestClock();
    private Path file;
    private PartySpeedQuizService service;

    static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-12-26T11:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("party-speed-state.json");
        service = serviceOn(file);
    }

    private PartySpeedQuizService serviceOn(Path stateFile) {
        return new PartySpeedQuizService(partyItemRepository,
                new PartySpeedHolder(objectMapper, stateFile.toString()), clock);
    }

    private void words(String topic, String... answers) {
        for (String answer : answers) {
            PartyItem item = new PartyItem();
            item.setCategory(PartyCategory.SPEED);
            item.setSubCategory(topic);
            item.setPresentation(PartyPresentation.TEXT);
            item.setAnswer(answer);
            partyItemRepository.save(item);
        }
    }

    private String currentWord() {
        return service.consoleView().word();
    }

    // ---------- 설정 ----------

    @Test
    @DisplayName("[정상] 기본 설정은 전체 주제·90초·90초·말로·보드 표시")
    void defaultSetup() {
        PartySpeedState.Setup setup = service.consoleView().setup();

        assertThat(setup.getTopic()).isNull();
        assertThat(setup.getLimitSeconds()).containsEntry(PartyTeam.A, 90).containsEntry(PartyTeam.B, 90);
        assertThat(setup.getMode()).isEqualTo(PartySpeedMode.TALK);
        assertThat(setup.isWordOnBoard()).isTrue();
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
    }

    @Test
    @DisplayName("[정상] 주제와 팀별 제한 시간을 정하면 그 주제의 제시어만, 그 팀의 시간으로 돈다")
    void configures() {
        words("동물", "코끼리", "기린");
        words("직업", "소방관");

        service.configure("동물", 60, 120, PartySpeedMode.BODY, true);
        service.start(PartyTeam.B);

        PartySpeedBoardView board = service.boardView();
        assertThat(board.phase()).isEqualTo("RUN");
        assertThat(board.turnTeam()).isEqualTo("B");
        assertThat(board.limitSeconds()).isEqualTo(120);
        assertThat(board.remainingSeconds()).isEqualTo(120);
        assertThat(board.mode()).isEqualTo("BODY");
        assertThat(board.word()).isIn("코끼리", "기린");
        assertThat(service.consoleView().topics()).containsEntry("동물", 1).containsEntry("직업", 1)
                .containsEntry(PartySpeedQuizService.ALL, 2);
    }

    @Test
    @DisplayName("[예외] 0 이하의 제한 시간, 없는 주제, 턴 진행 중의 설정 변경은 거부된다")
    void rejectsBadSetup() {
        words("동물", "코끼리", "기린");

        assertThatThrownBy(() -> service.configure(null, 0, 90, PartySpeedMode.TALK, true))
                .isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.configure(null, 90, -5, PartySpeedMode.TALK, true))
                .isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.configure("없는 주제", 90, 90, PartySpeedMode.TALK, true))
                .isInstanceOf(PartyGameException.class);

        service.start(PartyTeam.A);
        assertThatThrownBy(() -> service.configure(null, 30, 30, PartySpeedMode.TALK, true))
                .isInstanceOf(PartyGameException.class);
        assertThat(service.consoleView().setup().getLimitSeconds()).containsEntry(PartyTeam.A, 90);
    }

    // ---------- 턴 ----------

    @Test
    @DisplayName("[정상] 정답은 정답 수, 패스는 패스 수를 올리고 둘 다 다음 제시어로 넘어간다")
    void correctAndPass() {
        words("동물", "코끼리", "기린", "사자", "호랑이");
        service.start(PartyTeam.A);
        Set<String> seen = new HashSet<>();
        seen.add(currentWord());

        service.correct();
        seen.add(currentWord());
        service.pass();
        seen.add(currentWord());
        service.correct();
        seen.add(currentWord());

        assertThat(seen).hasSize(4);
        PartySpeedBoardView board = service.boardView();
        assertThat(board.results().get("A").correct()).isEqualTo(2);
        assertThat(board.results().get("A").pass()).isEqualTo(1);
        assertThat(board.results().get("B").correct()).isZero();
    }

    @Test
    @DisplayName("[예외] 한 번 나온 제시어는 상대 팀 턴에도 다시 나오지 않고, 다 떨어지면 턴이 끝난다")
    void wordsNeverRepeat() {
        words("동물", "코끼리", "기린", "사자");
        service.start(PartyTeam.A);
        String first = currentWord();
        service.correct();
        String second = currentWord();
        service.finish();

        service.start(PartyTeam.B);
        String third = currentWord();
        assertThat(Set.of(first, second, third)).hasSize(3);

        service.correct();

        assertThat(service.boardView().results().get("B").done()).isTrue();
        assertThat(service.consoleView().word()).isNull();
        assertThatThrownBy(() -> service.correct()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[예외] 남은 제시어가 없으면 턴을 시작할 수 없다")
    void cannotStartWithoutWords() {
        assertThatThrownBy(() -> service.start(PartyTeam.A)).isInstanceOf(PartyGameException.class);
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
    }

    @Test
    @DisplayName("[예외] 턴이 없을 때의 정답·패스·턴 종료는 거부된다")
    void needsTurn() {
        words("동물", "코끼리");

        assertThatThrownBy(() -> service.correct()).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.pass()).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.finish()).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.undo()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[경계] 제한 시간 +1초까지의 정답은 받고, 그 뒤는 거부된다")
    void timeLimitWithGrace() {
        words("동물", "코끼리", "기린", "사자", "호랑이", "하마");
        service.configure(null, 60, 60, PartySpeedMode.TALK, true);
        service.start(PartyTeam.A);

        clock.advance(59);
        service.correct();
        assertThat(service.boardView().remainingSeconds()).isEqualTo(1);

        clock.advance(2);
        assertThat(service.boardView().remainingSeconds()).isZero();
        assertThat(service.boardView().phase()).isEqualTo("OVER");
        service.correct();

        clock.advance(1);
        assertThatThrownBy(() -> service.correct()).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.pass()).isInstanceOf(PartyGameException.class);
        assertThat(service.boardView().results().get("A").correct()).isEqualTo(2);
        assertThat(service.boardView().results().get("A").done()).isTrue();
    }

    @Test
    @DisplayName("[예외] 한 팀의 턴이 진행 중이면 다른 팀을 시작할 수 없고, 마친 팀은 다시 시작할 수 없다")
    void oneTurnAtATime() {
        words("동물", "코끼리", "기린", "사자", "호랑이");
        service.start(PartyTeam.A);

        assertThatThrownBy(() -> service.start(PartyTeam.B)).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.start(PartyTeam.A)).isInstanceOf(PartyGameException.class);

        service.finish();
        assertThatThrownBy(() -> service.start(PartyTeam.A)).isInstanceOf(PartyGameException.class);
        service.start(PartyTeam.B);
        assertThat(service.boardView().turnTeam()).isEqualTo("B");
    }

    @Test
    @DisplayName("[정상] 시간이 끝난 팀 다음에 상대 팀을 바로 시작할 수 있다(턴 종료를 누르지 않아도)")
    void nextTeamAfterExpiry() {
        words("동물", "코끼리", "기린", "사자");
        service.start(PartyTeam.A);
        service.correct();

        clock.advance(200);
        service.start(PartyTeam.B);

        assertThat(service.boardView().turnTeam()).isEqualTo("B");
        assertThat(service.boardView().results().get("A").done()).isTrue();
        assertThat(service.boardView().results().get("A").correct()).isEqualTo(1);
    }

    // ---------- 되돌리기 ----------

    @Test
    @DisplayName("[정상] 되돌리기는 직전 정답·패스 1번을 취소하고 그 제시어를 다시 띄운다")
    void undoRestoresWord() {
        words("동물", "코끼리", "기린");
        service.start(PartyTeam.A);
        String first = currentWord();
        service.correct();
        String second = currentWord();

        service.undo();

        assertThat(currentWord()).isEqualTo(first);
        assertThat(service.boardView().results().get("A").correct()).isZero();
        assertThat(service.consoleView().canUndo()).isFalse();
        assertThatThrownBy(() -> service.undo()).isInstanceOf(PartyGameException.class);

        service.pass();
        assertThat(currentWord()).isEqualTo(second);
        service.undo();
        assertThat(service.boardView().results().get("A").pass()).isZero();
    }

    @Test
    @DisplayName("[정상] 시간이 끝난 뒤의 되돌리기는 수만 줄이고 제시어는 다시 띄우지 않는다")
    void undoAfterTimeOver() {
        words("동물", "코끼리", "기린", "사자");
        service.start(PartyTeam.A);
        service.correct();
        service.correct();
        clock.advance(200);

        service.undo();

        PartySpeedBoardView board = service.boardView();
        assertThat(board.results().get("A").correct()).isEqualTo(1);
        assertThat(board.phase()).isEqualTo("OVER");
        assertThat(board.word()).isNull();
        assertThat(service.consoleView().word()).isNull();
    }

    // ---------- 결과 ----------

    @Test
    @DisplayName("[정상] 두 팀이 끝나면 정답 수가 많은 팀이 첫 선택권, 동점이면 없음")
    void result() {
        words("동물", "코끼리", "기린", "사자", "호랑이", "하마", "타조");
        service.start(PartyTeam.A);
        service.correct();
        service.finish();
        assertThat(service.boardView().firstPick()).isNull();
        assertThat(service.boardView().phase()).isEqualTo("OVER");

        service.start(PartyTeam.B);
        service.correct();
        service.correct();
        service.finish();

        assertThat(service.boardView().phase()).isEqualTo("RESULT");
        assertThat(service.boardView().firstPick()).isEqualTo("B");

        service.undo();
        assertThat(service.boardView().phase()).isEqualTo("RESULT");
        assertThat(service.boardView().firstPick()).isNull();
    }

    @Test
    @DisplayName("[정상] 재대결은 결과만 비우고 나온 제시어 기록과 설정은 유지한다")
    void rematchKeepsUsedWords() {
        words("동물", "코끼리", "기린", "사자");
        service.configure(null, 30, 30, PartySpeedMode.TALK, true);
        service.start(PartyTeam.A);
        String first = currentWord();
        service.correct();
        String second = currentWord();
        service.finish();

        service.rematch();

        PartySpeedBoardView board = service.boardView();
        assertThat(board.phase()).isEqualTo("WAIT");
        assertThat(board.results().get("A").correct()).isZero();
        assertThat(board.results().get("A").done()).isFalse();
        assertThat(service.consoleView().setup().getLimitSeconds()).containsEntry(PartyTeam.A, 30);

        service.start(PartyTeam.A);
        assertThat(currentWord()).isNotIn(first, second);
    }

    @Test
    @DisplayName("[예외] 턴이 진행 중일 때의 재대결은 거부된다")
    void cannotRematchWhileRunning() {
        words("동물", "코끼리", "기린");
        service.start(PartyTeam.A);

        assertThatThrownBy(() -> service.rematch()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[연쇄] 스피드퀴즈는 본게임 점수를 바꾸지 않는다")
    void doesNotTouchMainGame() {
        gameService.newGame();
        words("동물", "코끼리", "기린");
        service.start(PartyTeam.A);
        service.correct();
        service.finish();

        assertThat(gameService.boardView().scores()).containsEntry("A", 0).containsEntry("B", 0);
        assertThat(gameService.boardView().round()).isZero();
    }

    // ---------- 노출 ----------

    @Test
    @DisplayName("[노출] '콘솔만' 이면 보드 상태에 제시어가 없고, 안 나온 제시어는 어디에도 없다")
    void boardHidesWord() throws Exception {
        words("동물", "코끼리", "기린", "사자");
        service.configure(null, 90, 90, PartySpeedMode.TALK, false);
        service.start(PartyTeam.A);
        String current = currentWord();

        String board = objectMapper.writeValueAsString(service.boardView());
        String console = objectMapper.writeValueAsString(service.consoleView());

        assertThat(service.boardView().word()).isNull();
        assertThat(board).doesNotContain("코끼리", "기린", "사자");
        assertThat(console).contains(current);
        for (String word : Set.of("코끼리", "기린", "사자")) {
            if (!word.equals(current)) {
                assertThat(console).doesNotContain(word);
            }
        }

        service.finish();
        service.rematch();
        service.configure(null, 90, 90, PartySpeedMode.TALK, true);
        service.start(PartyTeam.A);
        assertThat(service.boardView().word()).isEqualTo(currentWord()).isNotNull();
    }

    // ---------- 복구 ----------

    @Test
    @DisplayName("[정상] 앱을 다시 켜도 설정·결과·나온 제시어·진행 중인 턴의 남은 시간이 이어진다")
    void restoresAfterRestart() {
        words("동물", "코끼리", "기린", "사자");
        service.configure("동물", 60, 45, PartySpeedMode.BODY, false);
        service.start(PartyTeam.A);
        service.correct();
        String current = currentWord();
        clock.advance(20);

        PartySpeedQuizService restarted = serviceOn(file);

        PartySpeedBoardView board = restarted.boardView();
        assertThat(board.phase()).isEqualTo("RUN");
        assertThat(board.turnTeam()).isEqualTo("A");
        assertThat(board.remainingSeconds()).isEqualTo(40);
        assertThat(board.results().get("A").correct()).isEqualTo(1);
        assertThat(board.version()).isEqualTo(service.boardView().version());
        assertThat(restarted.consoleView().word()).isEqualTo(current);
        assertThat(restarted.consoleView().setup().getLimitSeconds()).containsEntry(PartyTeam.B, 45);
        assertThat(restarted.consoleView().setup().getMode()).isEqualTo(PartySpeedMode.BODY);
        assertThat(restarted.consoleView().canUndo()).isTrue();

        restarted.correct();
        assertThat(restarted.consoleView().word()).isNotNull().isNotEqualTo(current);
    }

    @Test
    @DisplayName("[정상] 초기화는 설정·결과·나온 제시어를 전부 비우고 저장 파일을 지운다")
    void resetClearsEverything() {
        words("동물", "코끼리");
        service.configure(null, 30, 30, PartySpeedMode.BODY, false);
        service.start(PartyTeam.A);
        service.correct();
        assertThat(file).exists();

        service.reset();

        assertThat(file).doesNotExist();
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
        assertThat(service.consoleView().setup().getLimitSeconds()).containsEntry(PartyTeam.A, 90);
        service.start(PartyTeam.A);
        assertThat(currentWord()).isEqualTo("코끼리");
    }
}
