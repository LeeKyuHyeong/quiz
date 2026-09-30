package com.kh.game.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kh.game.entity.Song;
import com.kh.game.entity.SongAnswer;
import com.kh.game.repository.GameSessionRepository;
import com.kh.game.repository.SongAnswerRepository;
import com.kh.game.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파티 본게임 규칙: 출제 · 판정 · 점수 · 힌트 · 재생 명령 · 보드 노출 · 이력 · 복구.
 * 게임 상태는 서버 전체에 1개라 테스트마다 새 게임으로 시작한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("파티 본게임 규칙")
class PartyGameServiceTest {

    @Autowired
    private PartyGameService service;
    @Autowired
    private PartyItemRepository partyItemRepository;
    @Autowired
    private PartySongRepository partySongRepository;
    @Autowired
    private SongRepository songRepository;
    @Autowired
    private SongAnswerRepository songAnswerRepository;
    @Autowired
    private GameSessionRepository gameSessionRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;
    @TempDir
    Path imageDir;

    @BeforeEach
    void setUp() {
        service.newGame();
    }

    // ---------- 준비 ----------

    private PartyItem item(PartyCategory category, String sub, PartyPresentation presentation, String answer) {
        PartyItem item = new PartyItem();
        item.setCategory(category);
        item.setSubCategory(sub);
        item.setPresentation(presentation);
        item.setAnswer(answer);
        if (presentation == PartyPresentation.AUDIO) {
            item.setYoutubeVideoId("abcdefghijk");
            item.setStartTime(12);
            item.setPlayDuration(6);
        } else if (presentation == PartyPresentation.IMAGE) {
            item.setImagePath(answer + ".jpg");
        } else {
            item.setQuestionText("[문제] " + answer);
        }
        return item;
    }

    private PartyItem save(PartyItem item) {
        return partyItemRepository.save(item);
    }

    private PartyItem text(String sub, String answer) {
        return save(item(PartyCategory.QUIZ, sub, PartyPresentation.TEXT, answer));
    }

    private PartyItem garen() {
        PartyItem item = item(PartyCategory.GAME, "리그 오브 레전드", PartyPresentation.AUDIO, "가렌");
        item.setAnswerAliases("가랜");
        item.setDetail("챔피언 대사");
        item.setSourceNote("라이엇 게임즈 코리아");
        item.setHint1("탑 · 전사");
        item.setHint2("데마시아 정예군 대장");
        item.setHint3("ㄱㄹ");
        return save(item);
    }

    private Song song(String title, Integer year) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist("가수 " + title);
        song.setYoutubeVideoId("abcdefghijk");
        song.setStartTime(30);
        song.setPlayDuration(10);
        song.setReleaseYear(year);
        song.setIsPopular(false);
        return songRepository.save(song);
    }

    /** 문제를 뽑아 보드에 띄운다. */
    private void showQuiz(String sub) {
        service.pick(PartyCategory.QUIZ, sub);
        service.show();
    }

    // ---------- 출제 ----------

    @Test
    @DisplayName("[정상] 고른 대분류·중분류 안에서 문제가 뽑히고, 띄우면 라운드가 오른다")
    void picksWithinSubCategory() {
        text("초성 · 과자", "포카칩");
        text("이모지 · 속담", "등잔 밑이 어둡다");
        save(item(PartyCategory.PERSON, "공통", PartyPresentation.IMAGE, "유재석"));

        service.pick(PartyCategory.QUIZ, "초성 · 과자");

        PartyConsoleView console = service.consoleView();
        assertThat(console.phase()).isEqualTo("READY");
        assertThat(console.card().answer()).isEqualTo("포카칩");
        assertThat(console.board().round()).isZero();

        service.show();

        assertThat(service.consoleView().phase()).isEqualTo("SHOW");
        assertThat(service.boardView().round()).isEqualTo(1);
        assertThat(service.boardView().item().questionText()).isEqualTo("[문제] 포카칩");
        assertThat(service.boardView().timerStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("[정상] 중분류 '전체'는 대분류 전체에서 뽑는다")
    void picksFromWholeCategory() {
        text("초성 · 과자", "포카칩");
        text("이모지 · 속담", "등잔 밑이 어둡다");

        Set<String> answers = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            service.pick(PartyCategory.QUIZ, PartyGameService.ALL);
            answers.add(service.consoleView().card().answer());
            service.show();
            service.miss();
        }

        assertThat(answers).containsExactlyInAnyOrder("포카칩", "등잔 밑이 어둡다");
    }

    @Test
    @DisplayName("[예외] 같은 문제는 한 게임에서 두 번 나오지 않고, 다 쓰면 거부된다")
    void neverRepeats() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");
        text("초성 · 과자", "꼬깔콘");

        Set<String> answers = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            showQuiz("초성 · 과자");
            answers.add(service.consoleView().card().answer());
            service.correct(PartyTeam.A);
        }

        assertThat(answers).hasSize(3);
        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);
        assertThat(service.consoleView().phase()).isEqualTo("REVEAL");
    }

    @Test
    @DisplayName("[예외] 낼 수 없는 문제는 뽑히지 않고 남은 수에도 세지 않는다")
    void skipsUnplayable() {
        PartyItem off = item(PartyCategory.SOUND, "CM송", PartyPresentation.AUDIO, "꺼 둔 문제");
        off.setUseYn("N");
        save(off);
        PartyItem noUrl = item(PartyCategory.SOUND, "CM송", PartyPresentation.AUDIO, "URL 없음");
        noUrl.setYoutubeVideoId(null);
        save(noUrl);
        PartyItem invalid = item(PartyCategory.SOUND, "CM송", PartyPresentation.AUDIO, "재생 불가");
        invalid.setIsYoutubeValid(false);
        save(invalid);
        PartyItem noImage = item(PartyCategory.ANIME, "국내", PartyPresentation.IMAGE, "파일명 없음");
        noImage.setImagePath(null);
        save(noImage);
        save(item(PartyCategory.SOUND, "CM송", PartyPresentation.AUDIO, "초코파이"));

        Map<String, Map<String, Integer>> remaining = service.remaining();
        assertThat(remaining.get("SOUND")).containsEntry("CM송", 1).containsEntry(PartyGameService.ALL, 1);
        assertThat(remaining.get("ANIME")).containsEntry(PartyGameService.ALL, 0);

        service.pick(PartyCategory.SOUND, "CM송");
        assertThat(service.consoleView().card().answer()).isEqualTo("초코파이");
        assertThatThrownBy(() -> service.pick(PartyCategory.ANIME, PartyGameService.ALL))
                .isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[예외] 스피드퀴즈 제시어는 본게임에서 뽑을 수 없다")
    void speedIsNotPickable() {
        save(item(PartyCategory.SPEED, "동물", PartyPresentation.TEXT, "코끼리"));

        assertThatThrownBy(() -> service.pick(PartyCategory.SPEED, PartyGameService.ALL))
                .isInstanceOf(PartyGameException.class);
        assertThat(service.remaining()).doesNotContainKey("SPEED");
    }

    @Test
    @DisplayName("[정상] 띄우기 전에 다시 뽑으면 앞서 뽑은 문제는 다시 나올 수 있다")
    void repickReleasesUnshownItem() {
        text("초성 · 과자", "포카칩");

        service.pick(PartyCategory.QUIZ, "초성 · 과자");
        service.pick(PartyCategory.QUIZ, "초성 · 과자");

        assertThat(service.consoleView().card().answer()).isEqualTo("포카칩");
    }

    @Test
    @DisplayName("[예외] 문제가 떠 있는 동안에는 새 문제를 뽑을 수 없다")
    void cannotPickWhileShowing() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");
        showQuiz("초성 · 과자");

        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[R-038] 다시 뽑기가 실패해도 들고 있던 문제는 쓴 것으로 남는다 (같은 문제가 두 번 나오지 않게)")
    void failedRepickKeepsHeldQuestionUsed() {
        text("초성 · 과자", "포카칩");
        service.pick(PartyCategory.QUIZ, "초성 · 과자");

        assertThatThrownBy(() -> service.pick(PartyCategory.PERSON, PartyGameService.ALL))
                .isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.pickSong(PartyGameService.ALL)).isInstanceOf(PartyGameException.class);

        assertThat(service.consoleView().phase()).isEqualTo("READY");
        assertThat(service.consoleView().card().answer()).isEqualTo("포카칩");
        assertThat(service.remaining().get("QUIZ")).containsEntry("초성 · 과자", 0);
        service.show();
        service.miss();
        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[R-038] 노래도 같다: 다시 뽑기가 실패하면 들고 있던 곡은 쓴 것으로 남는다")
    void failedRepickKeepsHeldSongUsed() {
        song("구십사", 1994);
        service.pickSong("1990~1994");

        assertThatThrownBy(() -> service.pickSong("2025~")).isInstanceOf(PartyGameException.class);

        service.show();
        service.miss();
        assertThatThrownBy(() -> service.pickSong(PartyGameService.ALL)).isInstanceOf(PartyGameException.class);
    }

    // ---------- 노래 ----------

    @Test
    @DisplayName("[경계] 노래 5년 묶음 경계: 1989·1990·1994·1995·2024·2025")
    void songBandBoundaries() {
        assertThat(PartySongBand.of(1989).label()).isEqualTo("1990 이전");
        assertThat(PartySongBand.of(1990).label()).isEqualTo("1990~1994");
        assertThat(PartySongBand.of(1994).label()).isEqualTo("1990~1994");
        assertThat(PartySongBand.of(1995).label()).isEqualTo("1995~1999");
        assertThat(PartySongBand.of(2024).label()).isEqualTo("2020~2024");
        assertThat(PartySongBand.of(2025).label()).isEqualTo("2025~");
        assertThat(PartySongBand.of(2031).label()).isEqualTo("2025~");
    }

    @Test
    @DisplayName("[정상] 노래는 고른 묶음에서만 나오고, 연도 없는 곡은 '전체'에서만 나온다")
    void picksSongByBand() {
        song("구십사", 1994);
        song("구십오", 1995);
        song("연도없음", null);

        assertThat(service.remaining().get(PartyGameService.SONG))
                .containsEntry("1990~1994", 1)
                .containsEntry("1995~1999", 1)
                .containsEntry("2025~", 0)
                .containsEntry(PartyGameService.ALL, 3);

        service.pickSong("1990~1994");
        assertThat(service.consoleView().card().answer()).isEqualTo("구십사");
        service.show();
        service.miss();
        assertThatThrownBy(() -> service.pickSong("1990~1994")).isInstanceOf(PartyGameException.class);

        Set<String> rest = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            service.pickSong(PartyGameService.ALL);
            rest.add(service.consoleView().card().answer());
            service.show();
            service.miss();
        }
        assertThat(rest).containsExactlyInAnyOrder("구십오", "연도없음");
        assertThatThrownBy(() -> service.pickSong("없는 묶음")).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[정상] 노래 정답 카드에 가수·연도와 정답 변형이 실린다 (비인기곡도 나온다)")
    void songCard() {
        Song song = song("Dynamite", 2020);
        SongAnswer alias = new SongAnswer();
        alias.setSong(song);
        alias.setAnswer("다이너마이트");
        songAnswerRepository.save(alias);

        service.pickSong("2020~2024");

        PartyConsoleView.Card card = service.consoleView().card();
        assertThat(card.answer()).isEqualTo("Dynamite");
        assertThat(card.aliases()).contains("다이너마이트");
        assertThat(card.detail()).contains("가수 Dynamite").contains("2020");
        service.show();
        assertThat(service.boardView().item().category()).isEqualTo(PartyGameService.SONG);
        assertThat(service.boardView().item().subCategory()).isEqualTo("2020~2024");
        assertThat(service.boardView().item().presentation()).isEqualTo("AUDIO");
        assertThat(service.boardView().item().startTime()).isEqualTo(30);
    }

    @Test
    @DisplayName("[예외] 꺼 둔 곡, 재생 불가 곡, 영상 없는 곡은 나오지 않는다")
    void skipsUnplayableSongs() {
        Song off = song("꺼 둔 곡", 2010);
        off.setUseYn("N");
        Song invalid = song("재생 불가", 2011);
        invalid.setIsYoutubeValid(false);
        Song noVideo = song("영상 없음", 2012);
        noVideo.setYoutubeVideoId(null);
        songRepository.saveAll(java.util.List.of(off, invalid, noVideo));

        assertThat(service.remaining().get(PartyGameService.SONG)).containsEntry(PartyGameService.ALL, 0);
        assertThatThrownBy(() -> service.pickSong(PartyGameService.ALL)).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[연쇄] 파티에서 노래를 내도 기존 게임 기록은 생기지 않는다")
    void doesNotTouchGameSession() {
        song("구십사", 1994);
        long before = gameSessionRepository.count();

        service.pickSong(PartyGameService.ALL);
        service.show();
        service.correct(PartyTeam.B);

        assertThat(gameSessionRepository.count()).isEqualTo(before);
    }

    // ---------- 판정·점수 ----------

    @Test
    @DisplayName("[정상] 팀 정답은 1점과 정답 공개, 못 맞힘은 점수 없이 정답 공개")
    void correctAndMiss() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");

        showQuiz("초성 · 과자");
        service.correct(PartyTeam.B);

        PartyBoardView board = service.boardView();
        assertThat(board.phase()).isEqualTo("REVEAL");
        assertThat(board.scores()).containsEntry("A", 0).containsEntry("B", 1);
        assertThat(board.reveal().scoringTeam()).isEqualTo("B");

        service.next();
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
        showQuiz("초성 · 과자");
        service.miss();

        board = service.boardView();
        assertThat(board.phase()).isEqualTo("REVEAL");
        assertThat(board.scores()).containsEntry("A", 0).containsEntry("B", 1);
        assertThat(board.reveal().scoringTeam()).isNull();
        assertThat(board.round()).isEqualTo(2);
    }

    @Test
    @DisplayName("[예외] 같은 판정을 두 번 눌러도 2점이 되지 않고, 문제가 없을 때의 판정은 거부된다")
    void judgingOnlyWhileShowing() {
        text("초성 · 과자", "포카칩");

        assertThatThrownBy(() -> service.correct(PartyTeam.A)).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.miss()).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.wrong(PartyTeam.A)).isInstanceOf(PartyGameException.class);

        service.pick(PartyCategory.QUIZ, "초성 · 과자");
        assertThatThrownBy(() -> service.correct(PartyTeam.A)).isInstanceOf(PartyGameException.class);

        service.show();
        service.correct(PartyTeam.A);
        assertThatThrownBy(() -> service.correct(PartyTeam.A)).isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.show()).isInstanceOf(PartyGameException.class);

        assertThat(service.boardView().scores()).containsEntry("A", 1);
        assertThat(service.consoleView().history()).hasSize(1);
    }

    @Test
    @DisplayName("[정상] 오답 표시는 보드에만 보이고 점수·이력에 남지 않는다")
    void wrongIsDisplayOnly() {
        text("초성 · 과자", "포카칩");
        showQuiz("초성 · 과자");

        service.wrong(PartyTeam.A);

        assertThat(service.boardView().wrongTeam()).isEqualTo("A");
        assertThat(service.boardView().phase()).isEqualTo("SHOW");
        assertThat(service.boardView().scores()).containsEntry("A", 0).containsEntry("B", 0);
        assertThat(service.consoleView().history()).isEmpty();

        service.correct(PartyTeam.B);
        service.next();
        assertThat(service.boardView().wrongTeam()).isNull();
    }

    @Test
    @DisplayName("[경계] 점수 정정은 어느 단계에서나 되고 0 아래로 내려가지 않는다")
    void adjustsScore() {
        service.adjustScore(PartyTeam.A, 1);
        service.adjustScore(PartyTeam.A, 1);
        service.adjustScore(PartyTeam.A, -1);
        service.adjustScore(PartyTeam.B, -1);

        assertThat(service.boardView().scores()).containsEntry("A", 1).containsEntry("B", 0);
    }

    // ---------- 힌트·재생 ----------

    @Test
    @DisplayName("[정상] GAME 은 힌트를 하나씩 열고, 3개를 넘으면 거부된다")
    void opensHintsInOrder() {
        garen();
        service.pick(PartyCategory.GAME, "리그 오브 레전드");
        service.show();
        assertThat(service.boardView().hints()).isEmpty();

        service.openHint();
        assertThat(service.boardView().hints()).containsExactly("탑 · 전사");
        service.openHint();
        service.openHint();
        assertThat(service.boardView().hints()).containsExactly("탑 · 전사", "데마시아 정예군 대장", "ㄱㄹ");

        assertThatThrownBy(() -> service.openHint()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[예외] GAME 이 아니면 힌트를 열 수 없다")
    void hintsOnlyForGame() {
        PartyItem quiz = item(PartyCategory.QUIZ, "초성 · 과자", PartyPresentation.TEXT, "포카칩");
        quiz.setHint1("과자");
        save(quiz);
        showQuiz("초성 · 과자");

        assertThatThrownBy(() -> service.openHint()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[정상] 재생 명령은 순번이 1씩 오르고, 재생할 것이 없는 문제에서는 거부된다")
    void playerCommands() {
        garen();
        text("초성 · 과자", "포카칩");

        service.pick(PartyCategory.GAME, "리그 오브 레전드");
        assertThatThrownBy(() -> service.play()).isInstanceOf(PartyGameException.class);
        service.show();
        long seq = service.boardView().player().seq();

        service.play();
        assertThat(service.boardView().player().cmd()).isEqualTo("PLAY");
        assertThat(service.boardView().player().seq()).isEqualTo(seq + 1);
        service.pause();
        assertThat(service.boardView().player().cmd()).isEqualTo("PAUSE");
        service.restart();
        assertThat(service.boardView().player().cmd()).isEqualTo("RESTART");
        assertThat(service.boardView().player().seq()).isEqualTo(seq + 3);

        service.correct(PartyTeam.A);
        service.play();
        service.next();

        showQuiz("초성 · 과자");
        assertThatThrownBy(() -> service.play()).isInstanceOf(PartyGameException.class);
    }

    // ---------- 노출 ----------

    @Test
    @DisplayName("[노출] 보드 상태에는 정답 공개 전까지 정답·인정답안·보조·출처·안 연 힌트가 없다")
    void boardHidesAnswerUntilReveal() throws Exception {
        garen();
        service.pick(PartyCategory.GAME, "리그 오브 레전드");

        String ready = objectMapper.writeValueAsString(service.boardView());
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
        assertThat(service.boardView().item()).isNull();
        assertThat(ready).doesNotContain("가렌", "가랜", "챔피언 대사", "라이엇", "탑 · 전사", "ㄱㄹ", "abcdefghijk");

        service.show();
        service.openHint();
        String showing = objectMapper.writeValueAsString(service.boardView());
        assertThat(showing).contains("리그 오브 레전드", "abcdefghijk", "탑 · 전사");
        assertThat(showing).doesNotContain("가렌", "가랜", "챔피언 대사", "라이엇", "데마시아", "ㄱㄹ");
        assertThat(service.boardView().reveal()).isNull();

        assertThat(service.consoleView().card().answer()).isEqualTo("가렌");
        assertThat(service.consoleView().card().aliases()).isEqualTo("가랜");
        assertThat(service.consoleView().card().hints()).hasSize(3);

        service.correct(PartyTeam.A);
        PartyBoardView.Reveal reveal = service.boardView().reveal();
        assertThat(reveal.answer()).isEqualTo("가렌");
        assertThat(reveal.detail()).isEqualTo("챔피언 대사");
        assertThat(reveal.source()).isEqualTo("라이엇 게임즈 코리아");
    }

    @Test
    @DisplayName("[정상] 상태가 바뀔 때마다 보드 버전이 오른다")
    void versionIncreases() {
        text("초성 · 과자", "포카칩");
        long v0 = service.boardView().version();

        service.pick(PartyCategory.QUIZ, "초성 · 과자");
        long v1 = service.boardView().version();
        service.show();
        long v2 = service.boardView().version();

        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
        assertThat(service.boardView().version()).isEqualTo(v2);
    }

    @Test
    @DisplayName("[R-034] 새 게임 뒤에도 보드 버전은 줄지 않는다 (보드가 같은 버전을 건너뛰지 않게)")
    void versionNeverGoesBackOnNewGame() {
        text("초성 · 과자", "포카칩");
        showQuiz("초성 · 과자");
        service.correct(PartyTeam.A);
        long before = service.boardView().version();

        service.newGame();

        assertThat(service.boardView().version()).isGreaterThan(before);
    }

    // ---------- 사진 파일 ----------

    @Test
    @DisplayName("[R-036] 사진 문제는 파일이 폴더에 실제로 있어야 낼 수 있다 (파일명만으로는 안 된다)")
    void imageNeedsRealFile() throws IOException {
        save(item(PartyCategory.PERSON, "공통", PartyPresentation.IMAGE, "유재석"));
        PartyGameService game = serviceOn(tempDir.resolve("party-state.json"));

        assertThat(game.remaining().get("PERSON")).containsEntry(PartyGameService.ALL, 0);
        assertThatThrownBy(() -> game.pick(PartyCategory.PERSON, "공통")).isInstanceOf(PartyGameException.class);

        Files.writeString(imageDir.resolve("유재석.jpg"), "사진 자리");

        assertThat(game.remaining().get("PERSON")).containsEntry("공통", 1);
        game.pick(PartyCategory.PERSON, "공통");
        game.show();
        assertThat(java.net.URLDecoder.decode(game.boardView().item().imageUrl(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("/admin/party/images/유재석.jpg");
        assertThat(game.boardView().item().imageUrl()).isEqualTo("/admin/party/images/%EC%9C%A0%EC%9E%AC%EC%84%9D.jpg");
    }

    @Test
    @DisplayName("[권한] 사진 폴더 밖을 가리키는 파일명은 없는 파일로 본다")
    void imageNameCannotEscapeFolder() throws IOException {
        Files.writeString(tempDir.resolve("outside.jpg"), "폴더 밖");
        Path inside = Files.createDirectories(tempDir.resolve("images"));
        Files.writeString(inside.resolve("ok.jpg"), "폴더 안");
        PartyImageStore store = new PartyImageStore(inside.toString());

        assertThat(store.exists("ok.jpg")).isTrue();
        assertThat(store.exists("../outside.jpg")).isFalse();
        assertThat(store.exists("..\\outside.jpg")).isFalse();
        assertThat(store.exists(tempDir.resolve("outside.jpg").toString())).isFalse();
        assertThat(store.exists("")).isFalse();
        assertThat(store.exists(null)).isFalse();
        assertThat(new PartyImageStore("").exists("ok.jpg")).isFalse();
    }

    @Test
    @DisplayName("[R-042] 윈도우에서 못 쓰는 글자가 든 파일명은 없는 사진일 뿐, 다른 문제까지 막지 않는다")
    void reservedCharacterInImageNameDoesNotBreakEverything() {
        PartyItem bad = item(PartyCategory.PERSON, "공통", PartyPresentation.IMAGE, "포스터");
        bad.setImagePath("poster?.jpg");
        save(bad);
        text("초성 · 과자", "포카칩");
        PartyGameService game = serviceOn(tempDir.resolve("party-state.json"));

        assertThat(game.remaining().get("PERSON")).containsEntry(PartyGameService.ALL, 0);
        assertThat(game.remaining().get("QUIZ")).containsEntry(PartyGameService.ALL, 1);
        game.pick(PartyCategory.QUIZ, "초성 · 과자");
        assertThat(game.consoleView().card().answer()).isEqualTo("포카칩");
    }

    // ---------- 버전 확인 ----------

    @Test
    @DisplayName("[R-037] 같은 버튼을 두 번 누르면 두 번째는 적용되지 않는다 (화면이 본 버전과 다름)")
    void staleActionIsRejected() {
        long seen = service.boardView().version();

        service.apply(seen, () -> service.adjustScore(PartyTeam.A, 1));
        assertThatThrownBy(() -> service.apply(seen, () -> service.adjustScore(PartyTeam.A, 1)))
                .isInstanceOf(PartyStaleException.class);

        assertThat(service.boardView().scores()).containsEntry("A", 1);
        long now = service.boardView().version();
        PartyConsoleView after = service.apply(now, () -> service.adjustScore(PartyTeam.A, 1));
        assertThat(after.board().scores()).containsEntry("A", 2);
    }

    @Test
    @DisplayName("[예외] 값이 틀린 것과 단계가 틀린 것을 구분한다")
    void inputErrorsAreDistinct() {
        assertThatThrownBy(() -> service.pickSong("없는 묶음")).isInstanceOf(PartyInputException.class);
        assertThatThrownBy(() -> service.pick(PartyCategory.SPEED, null)).isInstanceOf(PartyInputException.class);
        assertThatThrownBy(() -> service.miss())
                .isInstanceOf(PartyGameException.class).isNotInstanceOf(PartyInputException.class);
        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "없는 중분류"))
                .isInstanceOf(PartyGameException.class).isNotInstanceOf(PartyInputException.class);
    }

    // ---------- 띄우기 전 확인 · 거두기 · 다음 판 ----------

    @Test
    @DisplayName("[R-047] 콘솔은 뽑은 순간부터 영상·시작초를 받는다 — 띄우기 전에 재생해 볼 수 있게. 보드에는 없다")
    void consoleGetsMediaBeforeShow() throws Exception {
        PartyItem garen = garen();
        garen.setDifficulty(3);
        save(garen);

        service.pick(PartyCategory.GAME, "리그 오브 레전드");

        PartyConsoleView console = service.consoleView();
        assertThat(console.phase()).isEqualTo("READY");
        assertThat(console.item().videoId()).isEqualTo("abcdefghijk");
        assertThat(console.item().startTime()).isEqualTo(12);
        assertThat(console.item().duration()).isEqualTo(6);
        assertThat(console.card().difficulty()).isEqualTo(3);
        assertThat(service.boardView().item()).isNull();
        assertThat(objectMapper.writeValueAsString(service.boardView())).doesNotContain("abcdefghijk");
    }

    @Test
    @DisplayName("[R-047] 띄운 문제를 정답 공개 없이 거둘 수 있다 — 라운드·이력에 남지 않고 다시 나오지도 않는다")
    void cancelShowWithoutReveal() throws Exception {
        text("초성 · 과자", "포카칩");
        showQuiz("초성 · 과자");
        assertThat(service.boardView().round()).isEqualTo(1);

        service.cancelShow();

        PartyBoardView board = service.boardView();
        assertThat(board.phase()).isEqualTo("WAIT");
        assertThat(board.round()).isZero();
        assertThat(board.reveal()).isNull();
        assertThat(objectMapper.writeValueAsString(board)).doesNotContain("포카칩");
        assertThat(service.consoleView().history()).isEmpty();
        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);
        assertThatThrownBy(() -> service.cancelShow()).isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[R-048] 다음 판은 점수·이력만 비우고, 앞 판에 낸 문제와 곡은 다시 나오지 않는다")
    void nextGameKeepsUsedQuestions() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");
        song("구십사", 1994);
        showQuiz("초성 · 과자");
        service.correct(PartyTeam.A);
        String first = service.consoleView().history().get(0).getAnswer();
        service.pickSong(PartyGameService.ALL);
        service.show();
        service.miss();

        service.newGame(true);

        assertThat(service.boardView().scores()).containsEntry("A", 0);
        assertThat(service.boardView().round()).isZero();
        assertThat(service.consoleView().history()).isEmpty();
        assertThat(service.remaining().get("QUIZ")).containsEntry("초성 · 과자", 1);
        service.pick(PartyCategory.QUIZ, "초성 · 과자");
        assertThat(service.consoleView().card().answer()).isNotEqualTo(first);
        assertThatThrownBy(() -> service.pickSong(PartyGameService.ALL)).isInstanceOf(PartyGameException.class);

        service.newGame(false);
        assertThat(service.remaining().get("QUIZ")).containsEntry("초성 · 과자", 2);
    }

    @Test
    @DisplayName("[정상] 타이머 경과 시간은 서버가 잰다 (문제가 떠 있을 때만)")
    void timerElapsedComesFromServer() {
        text("초성 · 과자", "포카칩");
        assertThat(service.boardView().timerElapsedSeconds()).isNull();

        showQuiz("초성 · 과자");

        assertThat(service.boardView().timerElapsedSeconds()).isBetween(0, 2);
    }

    // ---------- 점수판·종료 ----------

    @Test
    @DisplayName("[정상] 점수판·대기 전환과 종료. 종료 뒤에는 새 게임만 된다")
    void scoresAndEnd() {
        text("초성 · 과자", "포카칩");

        service.showScores();
        assertThat(service.boardView().phase()).isEqualTo("SCORES");
        service.backToWait();
        assertThat(service.boardView().phase()).isEqualTo("WAIT");

        service.end();
        assertThat(service.boardView().phase()).isEqualTo("END");
        assertThatThrownBy(() -> service.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);

        service.newGame();
        assertThat(service.boardView().phase()).isEqualTo("WAIT");
    }

    @Test
    @DisplayName("[R-040] 잘못 누른 종료는 되돌릴 수 있다 — 점수·이력·낸 문제가 그대로 이어진다")
    void endCanBeUndone() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");
        showQuiz("초성 · 과자");
        service.correct(PartyTeam.A);
        service.end();

        service.backToWait();

        assertThat(service.boardView().phase()).isEqualTo("WAIT");
        assertThat(service.boardView().scores()).containsEntry("A", 1);
        assertThat(service.consoleView().history()).hasSize(1);
        service.pick(PartyCategory.QUIZ, "초성 · 과자");
        assertThat(service.consoleView().card().answer()).isIn("포카칩", "홈런볼");
    }

    // ---------- 이력·복구 ----------

    @Test
    @DisplayName("[정상] 라운드마다 대분류·중분류·정답·득점 팀이 이력에 남는다")
    void recordsHistory() {
        text("초성 · 과자", "포카칩");
        garen();

        showQuiz("초성 · 과자");
        service.correct(PartyTeam.A);
        service.pick(PartyCategory.GAME, "리그 오브 레전드");
        service.show();
        service.miss();

        assertThat(service.consoleView().history())
                .extracting(PartyGameState.HistoryEntry::getRound, PartyGameState.HistoryEntry::getCategory,
                        PartyGameState.HistoryEntry::getSubCategory, PartyGameState.HistoryEntry::getAnswer,
                        PartyGameState.HistoryEntry::getScoringTeam)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "QUIZ", "초성 · 과자", "포카칩", PartyTeam.A),
                        org.assertj.core.groups.Tuple.tuple(2, "GAME", "리그 오브 레전드", "가렌", null));
    }

    private PartyGameService serviceOn(Path file) {
        return new PartyGameService(partyItemRepository, partySongRepository, songAnswerRepository,
                new PartyGameHolder(objectMapper, file.toString()), new PartyImageStore(imageDir.toString()));
    }

    @Test
    @DisplayName("[정상] 앱을 다시 켜면 점수·라운드·낸 문제·현재 단계가 이어진다")
    void restoresAfterRestart() {
        text("초성 · 과자", "포카칩");
        text("초성 · 과자", "홈런볼");
        Path file = tempDir.resolve("state/party-state.json");

        PartyGameService first = serviceOn(file);
        first.pick(PartyCategory.QUIZ, "초성 · 과자");
        first.show();
        first.correct(PartyTeam.B);
        String firstAnswer = first.boardView().reveal().answer();
        first.pick(PartyCategory.QUIZ, "초성 · 과자");
        first.show();
        first.wrong(PartyTeam.A);
        String secondAnswer = first.consoleView().card().answer();

        PartyGameService second = serviceOn(file);

        assertThat(second.boardView().phase()).isEqualTo("SHOW");
        assertThat(second.boardView().round()).isEqualTo(2);
        assertThat(second.boardView().scores()).containsEntry("B", 1);
        assertThat(second.boardView().wrongTeam()).isEqualTo("A");
        assertThat(second.boardView().version()).isGreaterThan(first.boardView().version());
        assertThat(second.consoleView().card().answer()).isEqualTo(secondAnswer).isNotEqualTo(firstAnswer);
        assertThat(second.consoleView().history()).hasSize(1);

        second.miss();
        assertThatThrownBy(() -> second.pick(PartyCategory.QUIZ, "초성 · 과자"))
                .isInstanceOf(PartyGameException.class);
    }

    @Test
    @DisplayName("[정상] 새 게임은 점수·이력·낸 문제를 비우고 저장 파일을 지운다")
    void newGameClearsEverything() {
        text("초성 · 과자", "포카칩");
        Path file = tempDir.resolve("party-state.json");
        PartyGameService game = serviceOn(file);
        game.pick(PartyCategory.QUIZ, "초성 · 과자");
        game.show();
        game.correct(PartyTeam.A);
        assertThat(file).exists();

        game.newGame();

        assertThat(file).doesNotExist();
        assertThat(game.boardView().scores()).containsEntry("A", 0);
        assertThat(game.boardView().round()).isZero();
        assertThat(game.consoleView().history()).isEmpty();
        game.pick(PartyCategory.QUIZ, "초성 · 과자");
        assertThat(game.consoleView().card().answer()).isEqualTo("포카칩");
    }

    @Test
    @DisplayName("[R-040] 새 게임은 지우기 전의 상태를 .bak 으로 남긴다 (잘못 누른 새 게임 복구용)")
    void newGameKeepsBackup() throws IOException {
        text("초성 · 과자", "포카칩");
        Path file = tempDir.resolve("party-state.json");
        PartyGameService game = serviceOn(file);
        game.pick(PartyCategory.QUIZ, "초성 · 과자");
        game.show();
        game.correct(PartyTeam.B);

        game.newGame();
        game.adjustScore(PartyTeam.A, 1);
        game.newGame();

        java.util.List<Path> backups;
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            backups = files.filter(p -> p.getFileName().toString().startsWith("party-state.json.bak-"))
                    .sorted().toList();
        }
        assertThat(file).doesNotExist();
        assertThat(backups).as("두 번째 새 게임이 첫 백업을 덮어쓰지 않는다").hasSize(2);
        Files.move(backups.get(0), file);
        assertThat(serviceOn(file).boardView().scores()).containsEntry("B", 1);
    }

    @Test
    @DisplayName("[R-041] 저장 도중 꺼져서 본 파일이 없고 .tmp 만 남았으면 .tmp 로 이어간다")
    void fallsBackToTempSnapshot() throws IOException {
        text("초성 · 과자", "포카칩");
        Path file = tempDir.resolve("party-state.json");
        PartyGameService game = serviceOn(file);
        game.pick(PartyCategory.QUIZ, "초성 · 과자");
        game.show();
        game.correct(PartyTeam.A);
        Files.move(file, tempDir.resolve("party-state.json.tmp"));

        PartyGameService restarted = serviceOn(file);

        assertThat(restarted.boardView().scores()).containsEntry("A", 1);
        assertThat(restarted.boardView().round()).isEqualTo(1);
    }

    @Test
    @DisplayName("[R-046] 바꿔치기가 실패해 .tmp 가 본 파일보다 새것이면 .tmp 로 이어간다 (마지막 조작을 잃지 않는다)")
    void restoresNewerTempSnapshot() throws IOException {
        text("초성 · 과자", "포카칩");
        Path file = tempDir.resolve("party-state.json");
        PartyGameService game = serviceOn(file);
        game.adjustScore(PartyTeam.A, 1);
        Path older = tempDir.resolve("older.json");
        Files.copy(file, older);
        game.adjustScore(PartyTeam.A, 1);
        // 두 번째 저장의 바꿔치기가 실패한 상황: 본 파일은 앞 상태, .tmp 는 새 상태
        Files.move(file, tempDir.resolve("party-state.json.tmp"));
        Files.move(older, file);

        PartyGameService restarted = serviceOn(file);

        assertThat(restarted.boardView().scores()).containsEntry("A", 2);
        assertThat(restarted.boardView().version()).isGreaterThan(game.boardView().version());
    }

    @Test
    @DisplayName("[R-041] 읽을 수 없는 저장 파일은 덮어쓰지 않고 옆에 남긴다")
    void unreadableSnapshotIsKeptAside() throws IOException {
        text("초성 · 과자", "포카칩");
        Path file = tempDir.resolve("party-state.json");
        String broken = "{\"phase\":\"SHOW\",\"scores\":";
        Files.writeString(file, broken);

        PartyGameService game = serviceOn(file);
        game.pick(PartyCategory.QUIZ, "초성 · 과자");

        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            java.util.List<Path> kept = files
                    .filter(p -> p.getFileName().toString().startsWith("party-state.json.bad-")).toList();
            assertThat(kept).hasSize(1);
            assertThat(Files.readString(kept.get(0))).isEqualTo(broken);
        }
        assertThat(Files.readString(file)).contains("READY");
    }

    @Test
    @DisplayName("[예외] 저장 파일이 깨져 있으면 새 게임으로 시작한다")
    void brokenSnapshotStartsFresh() throws IOException {
        Path file = tempDir.resolve("party-state.json");
        Files.writeString(file, "{\"phase\":\"SHOW\",\"scores\":");

        PartyGameService game = serviceOn(file);

        assertThat(game.boardView().phase()).isEqualTo("WAIT");
        assertThat(game.boardView().scores()).containsEntry("A", 0).containsEntry("B", 0);
    }
}
