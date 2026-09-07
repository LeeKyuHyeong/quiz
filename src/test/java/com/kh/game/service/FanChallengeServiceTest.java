package com.kh.game.service;

import com.kh.game.entity.*;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.GameSessionRepository;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.SongAnswerRepository;
import com.kh.game.repository.SongRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("FanChallengeService 테스트")
class FanChallengeServiceTest {

    @Autowired
    private FanChallengeService fanChallengeService;

    @Autowired
    private SongService songService;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private SongAnswerRepository songAnswerRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private GameSessionRepository gameSessionRepository;

    @MockitoBean
    private YouTubeValidationService youTubeValidationService;

    private Genre kpopGenre;

    @BeforeEach
    void setUp() {
        // 기존 데이터 정리
        gameSessionRepository.deleteAll();
        songAnswerRepository.deleteAll();
        songRepository.deleteAll();
        genreRepository.deleteAll();

        // YouTube 검증 항상 성공하도록 Mock 설정
        YouTubeValidationService.ValidationResult validResult = YouTubeValidationService.ValidationResult.valid();
        Mockito.when(youTubeValidationService.validateVideo(anyString())).thenReturn(validResult);

        // 장르 생성
        kpopGenre = new Genre();
        kpopGenre.setCode("KPOP");
        kpopGenre.setName("K-POP");
        kpopGenre.setUseYn("Y");
        kpopGenre = genreRepository.save(kpopGenre);
    }

    private Song createSong(String title, String artist) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setGenre(kpopGenre);
        song.setUseYn("Y");
        song.setIsSolo(false);
        song.setYoutubeVideoId("test" + System.nanoTime());
        song.setStartTime(0);
        song.setPlayDuration(30);
        Song saved = songRepository.save(song);

        // 정답 추가
        SongAnswer answer = new SongAnswer();
        answer.setSong(saved);
        answer.setAnswer(title);
        answer.setIsPrimary(true);
        songAnswerRepository.save(answer);

        return saved;
    }

    private void createSongsForArtist(String artist, int count) {
        for (int i = 1; i <= count; i++) {
            createSong(artist + " Song " + i, artist);
        }
    }

    // =====================================================
    // Edge Case 1: 최소 곡 수 요구사항
    // =====================================================
    @Nested
    @DisplayName("최소 곡 수 요구사항")
    class MinimumSongRequirementScenarios {

        @Test
        @DisplayName("20곡 미만 아티스트로 게임 시작 시 예외 발생")
        void lessThan20Songs_shouldThrowException() {
            // Given: 19곡인 아티스트
            for (int i = 1; i <= 19; i++) {
                createSong("Song " + i, "Few Songs Artist");
            }

            // When & Then
            assertThatThrownBy(() -> fanChallengeService.startChallenge(
                    null, "테스터", "Few Songs Artist", FanChallengeDifficulty.HARDCORE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다");
        }

        @Test
        @DisplayName("정확히 19곡인 아티스트는 게임 시작 불가 (경계값-1)")
        void exactly19Songs_shouldThrowException() {
            // Given: 정확히 19곡 (경계값 - 1)
            for (int i = 1; i <= 19; i++) {
                createSong("Song " + i, "Boundary Artist");
            }

            // When & Then
            assertThatThrownBy(() -> fanChallengeService.startChallenge(
                    null, "테스터", "Boundary Artist", FanChallengeDifficulty.HARDCORE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다")
                    .hasMessageContaining("현재 19곡");
        }

        @Test
        @DisplayName("정확히 20곡인 아티스트로 게임 시작 가능")
        void exactly20Songs_shouldStartSuccessfully() {
            // Given: 20곡인 아티스트
            for (int i = 1; i <= 20; i++) {
                createSong("Song " + i, "Exact Artist");
            }

            // When
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Exact Artist", FanChallengeDifficulty.HARDCORE);

            // Then
            assertThat(session).isNotNull();
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }

        @Test
        @DisplayName("21곡인 아티스트는 20곡만 출제 (경계값+1)")
        void exactly21Songs_shouldStartAndSelect20() {
            // Given: 21곡인 아티스트
            for (int i = 1; i <= 21; i++) {
                createSong("Song " + i, "OneOver Artist");
            }

            // When
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "OneOver Artist", FanChallengeDifficulty.HARDCORE);

            // Then
            assertThat(session).isNotNull();
            assertThat(session.getTotalRounds()).isEqualTo(20);
            assertThat(session.getRounds().size()).isEqualTo(20);
        }

        @Test
        @DisplayName("50곡인 아티스트도 20곡만 출제")
        void moreThan20Songs_shouldSelect20Only() {
            // Given: 50곡인 아티스트
            for (int i = 1; i <= 50; i++) {
                createSong("Song " + i, "Many Songs Artist");
            }

            // When
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Many Songs Artist", FanChallengeDifficulty.HARDCORE);

            // Then
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }
    }

    // =====================================================
    // Edge Case 2: 라이프 소진 시나리오
    // =====================================================
    @Nested
    @DisplayName("라이프 소진 시나리오")
    class LifeExhaustionScenarios {

        @Test
        @DisplayName("20곡 + 라이프 3개 + 3회 연속 실패 → LIFE_EXHAUSTED")
        void twentySongs_threeConsecutiveFails_lifeExhausted() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            assertThat(session.getTotalRounds()).isEqualTo(20);
            assertThat(session.getRemainingLives()).isEqualTo(3);

            // When: 3회 연속 실패
            fanChallengeService.processAnswer(session.getId(), 1, "틀림", 5000);
            fanChallengeService.processAnswer(session.getId(), 2, "틀림", 5000);
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 3, "틀림", 5000);

            // Then
            assertThat(result.isGameOver()).isTrue();
            assertThat(result.gameOverReason()).isEqualTo("LIFE_EXHAUSTED");
            assertThat(result.remainingLives()).isEqualTo(0);
            assertThat(result.completedRounds()).isEqualTo(3);
        }

        @Test
        @DisplayName("라이프가 딱 0이 되는 시점 + 라운드 완료 시점 동시 발생")
        void lifeExhaustedAndRoundsCompletedSimultaneously() {
            // Given: 곡 3개, 라이프 3개
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When: 3회 모두 실패 (라이프 0 + 라운드 완료 동시 발생)
            fanChallengeService.processAnswer(session.getId(), 1, "틀림", 5000);
            fanChallengeService.processAnswer(session.getId(), 2, "틀림", 5000);
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 3, "틀림", 5000);

            // Then: LIFE_EXHAUSTED가 우선해야 함 (먼저 체크됨)
            assertThat(result.isGameOver()).isTrue();
            assertThat(result.gameOverReason()).isEqualTo("LIFE_EXHAUSTED");
            assertThat(result.remainingLives()).isEqualTo(0);
            assertThat(result.completedRounds()).isEqualTo(3);
            assertThat(result.correctCount()).isEqualTo(0);
        }
    }

    // =====================================================
    // Edge Case 4: 시간 초과 시나리오
    // =====================================================
    @Nested
    @DisplayName("시간 초과 시나리오")
    class TimeoutScenarios {

        @Test
        @DisplayName("시간 초과 → 라이프 감소")
        void timeout_decreasesLife() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When
            FanChallengeService.AnswerResult result = fanChallengeService.processTimeout(
                    session.getId(), 1);

            // Then
            assertThat(result.isTimeout()).isTrue();
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.remainingLives()).isEqualTo(2);
        }

        @Test
        @DisplayName("시간 제한 초과한 정답 제출 → 시간 초과로 처리")
        void answerAfterTimeLimit_treatedAsTimeout() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // 하드코어 총 시간: 3초 + 5초 = 8초 = 8000ms
            FanChallengeDifficulty difficulty = FanChallengeDifficulty.HARDCORE;
            long timeLimit = difficulty.getTotalTimeMs();

            // 라운드 1의 실제 곡 제목 가져오기
            String round1SongTitle = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 1)
                    .findFirst()
                    .map(r -> r.getSong().getTitle())
                    .orElseThrow();

            // When: 시간 초과 후 정답 제출 (정답이지만 시간 초과)
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, round1SongTitle, timeLimit + 1);

            // Then
            assertThat(result.isTimeout()).isTrue();
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("시간 제한 내 정답 제출 → 정상 처리")
        void answerWithinTimeLimit_processedNormally() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            FanChallengeDifficulty difficulty = FanChallengeDifficulty.HARDCORE;
            long timeLimit = difficulty.getTotalTimeMs();

            // 라운드 1의 실제 곡 제목 가져오기
            String round1SongTitle = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 1)
                    .findFirst()
                    .map(r -> r.getSong().getTitle())
                    .orElseThrow();

            // When: 시간 내 정답 제출
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, round1SongTitle, timeLimit - 1);

            // Then
            assertThat(result.isTimeout()).isFalse();
            assertThat(result.isCorrect()).isTrue();
        }
    }

    // =====================================================
    // Edge Case 5: 잘못된 입력 시나리오
    // =====================================================
    @Nested
    @DisplayName("잘못된 입력 시나리오")
    class InvalidInputScenarios {

        @Test
        @DisplayName("빈 문자열 답안 → 오답 처리")
        void emptyAnswer_treatedAsWrong() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, "", 5000);

            // Then
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.remainingLives()).isEqualTo(2);
        }

        @Test
        @DisplayName("null 답안 → 오답 처리")
        void nullAnswer_treatedAsWrong() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, null, 5000);

            // Then
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("공백만 있는 답안 → 오답 처리")
        void whitespaceOnlyAnswer_treatedAsWrong() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, "   ", 5000);

            // Then
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("존재하지 않는 세션 ID → 예외 발생")
        void nonExistentSessionId_throwsException() {
            // When & Then
            assertThatThrownBy(() ->
                    fanChallengeService.processAnswer(999999L, 1, "답", 5000)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("세션을 찾을 수 없습니다");
        }

        @Test
        @DisplayName("존재하지 않는 라운드 번호 → 예외 발생")
        void nonExistentRoundNumber_throwsException() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // When & Then
            assertThatThrownBy(() ->
                    fanChallengeService.processAnswer(session.getId(), 999, "답", 5000)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("라운드를 찾을 수 없습니다");
        }

        @Test
        @DisplayName("곡이 없는 아티스트로 게임 시작 → 예외 발생")
        void noSongsForArtist_throwsException() {
            // When & Then: 0곡이면 "20곡 이상" 에러 메시지
            assertThatThrownBy(() ->
                    fanChallengeService.startChallenge(null, "테스터", "없는 아티스트", FanChallengeDifficulty.HARDCORE)
            ).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다")
                    .hasMessageContaining("현재 0곡");
        }
    }

    // =====================================================
    // Edge Case 6: 이미 완료된 게임 시나리오
    // =====================================================
    @Nested
    @DisplayName("이미 완료된 게임 시나리오")
    class CompletedGameScenarios {

        @Test
        @DisplayName("이미 완료된 게임에 답안 제출 → 예외 발생")
        void answerToCompletedGame_throwsException() {
            // Given: 20곡 생성 및 게임 완료
            createSongsForArtist("Solo Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Solo Artist", FanChallengeDifficulty.HARDCORE);

            // 모든 라운드 완료 (20곡 모두 정답 처리)
            for (int i = 1; i <= 20; i++) {
                final int roundNum = i;
                GameRound round = session.getRounds().stream()
                        .filter(r -> r.getRoundNumber() == roundNum)
                        .findFirst()
                        .orElseThrow();
                fanChallengeService.processAnswer(session.getId(), roundNum, round.getSong().getTitle(), 5000);
            }

            // 게임이 완료됨을 확인
            GameSession completedSession = fanChallengeService.getSession(session.getId());
            assertThat(completedSession.getStatus()).isEqualTo(GameSession.GameStatus.COMPLETED);

            // When & Then: 완료된 게임에 다시 답안 제출
            assertThatThrownBy(() ->
                    fanChallengeService.processAnswer(session.getId(), 1, "다른 답", 5000)
            ).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("게임이 진행 중이 아닙니다");
        }
    }

    // =====================================================
    // Edge Case 7: 난이도별 설정 시나리오
    // =====================================================
    @Nested
    @DisplayName("난이도별 설정 시나리오")
    class DifficultyScenarios {

        @Test
        @DisplayName("일반 모드 초기 라이프 3개")
        void normalMode_initialLives3() {
            // Given
            createSongsForArtist("Test Artist", 20);

            // When
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.NORMAL);

            // Then
            assertThat(session.getRemainingLives()).isEqualTo(3);
        }

        @Test
        @DisplayName("하드코어 모드 초기 라이프 3개 (수정됨)")
        void hardcoreMode_initialLives3() {
            // Given
            createSongsForArtist("Test Artist", 20);

            // When
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // Then
            assertThat(session.getRemainingLives()).isEqualTo(3);
        }

        @Test
        @DisplayName("난이도별 시간 제한 확인")
        void difficultyTimeSettings() {
            // 노말: 7초(재생) + 6초(입력) = 13초
            assertThat(FanChallengeDifficulty.NORMAL.getTotalTimeMs()).isEqualTo(13000);

            // 하드코어: 5초(재생) + 5초(입력) = 10초
            assertThat(FanChallengeDifficulty.HARDCORE.getTotalTimeMs()).isEqualTo(10000);
        }

        @Test
        @DisplayName("모든 난이도 초성 힌트 미제공")
        void noChosungHintForAllDifficulties() {
            assertThat(FanChallengeDifficulty.NORMAL.isShowChosungHint()).isFalse();
            assertThat(FanChallengeDifficulty.HARDCORE.isShowChosungHint()).isFalse();
        }

        @Test
        @DisplayName("하드코어만 공식 랭킹 반영")
        void onlyHardcoreIsRanked() {
            assertThat(FanChallengeDifficulty.NORMAL.isRanked()).isFalse();
            assertThat(FanChallengeDifficulty.HARDCORE.isRanked()).isTrue();
        }
    }

    // =====================================================
    // Edge Case 8: 초성 힌트 시나리오
    // =====================================================
    @Nested
    @DisplayName("초성 힌트 시나리오")
    class ChosungHintScenarios {

        @Test
        @DisplayName("한글 제목 초성 추출")
        void koreanTitle_extractChosung() {
            // Given & When & Then
            assertThat(fanChallengeService.extractChosung("봄날")).isEqualTo("ㅂㄴ");
            assertThat(fanChallengeService.extractChosung("피 땀 눈물")).isEqualTo("ㅍ ㄸ ㄴㅁ");
            assertThat(fanChallengeService.extractChosung("작은 것들을 위한 시")).isEqualTo("ㅈㅇ ㄱㄷㅇ ㅇㅎ ㅅ");
        }

        @Test
        @DisplayName("영어 제목 힌트 생성")
        void englishTitle_extractHint() {
            // Given & When & Then
            assertThat(fanChallengeService.extractChosung("Dynamite")).isEqualTo("D*******");
            assertThat(fanChallengeService.extractChosung("Boy With Luv")).isEqualTo("B** W*** L**");
        }

        @Test
        @DisplayName("숫자가 포함된 제목")
        void titleWithNumbers() {
            assertThat(fanChallengeService.extractChosung("2002")).isEqualTo("2002");
            assertThat(fanChallengeService.extractChosung("24시간이 모자라")).isEqualTo("24ㅅㄱㅇ ㅁㅈㄹ");
        }

        @Test
        @DisplayName("빈 제목")
        void emptyTitle() {
            assertThat(fanChallengeService.extractChosung("")).isEqualTo("");
            assertThat(fanChallengeService.extractChosung(null)).isEqualTo("");
        }
    }

    // =====================================================
    // Edge Case 9: 동시성/경계값 시나리오
    // =====================================================
    @Nested
    @DisplayName("경계값 시나리오")
    class BoundaryScenarios {

        @Test
        @DisplayName("정확히 시간 제한에 맞춘 답안 → 정상 처리")
        void answerExactlyAtTimeLimit_processedNormally() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            long timeLimit = FanChallengeDifficulty.HARDCORE.getTotalTimeMs();

            // When: 정확히 시간 제한에 맞춤
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, "Test Artist Song 1", timeLimit);

            // Then: 시간 초과가 아님 (timeLimit 초과가 아니라 timeLimit 이하)
            assertThat(result.isTimeout()).isFalse();
        }

        @Test
        @DisplayName("라이프가 정확히 1개 남은 상태에서 실패")
        void exactlyOneLifeRemaining_fail() {
            // Given
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // 2회 실패로 라이프 1개 남김
            fanChallengeService.processAnswer(session.getId(), 1, "틀림", 5000);
            fanChallengeService.processAnswer(session.getId(), 2, "틀림", 5000);

            GameSession afterTwoFails = fanChallengeService.getSession(session.getId());
            assertThat(afterTwoFails.getRemainingLives()).isEqualTo(1);

            // When: 마지막 라이프 소진
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 3, "틀림", 5000);

            // Then
            assertThat(result.isGameOver()).isTrue();
            assertThat(result.gameOverReason()).isEqualTo("LIFE_EXHAUSTED");
            assertThat(result.remainingLives()).isEqualTo(0);
        }

        @Test
        @DisplayName("마지막 라운드에서 정답 → 퍼펙트 클리어")
        void lastRound_correct() {
            // Given: 20곡 생성 및 게임 시작
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // 처음 19개 라운드 정답 처리
            for (int i = 1; i <= 19; i++) {
                final int roundNum = i;
                GameRound round = session.getRounds().stream()
                        .filter(r -> r.getRoundNumber() == roundNum)
                        .findFirst()
                        .orElseThrow();
                fanChallengeService.processAnswer(session.getId(), roundNum, round.getSong().getTitle(), 5000);
            }

            // When: 마지막 라운드(20번) 정답
            GameRound lastRound = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 20)
                    .findFirst()
                    .orElseThrow();
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 20, lastRound.getSong().getTitle(), 5000);

            // Then
            assertThat(result.isGameOver()).isTrue();
            assertThat(result.gameOverReason()).isEqualTo("PERFECT_CLEAR");
            assertThat(result.correctCount()).isEqualTo(20);
        }

        @Test
        @DisplayName("마지막 라운드에서 오답 (라이프 남음)")
        void lastRound_wrongWithLivesRemaining() {
            // Given: 20곡 생성 및 게임 시작
            createSongsForArtist("Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE);

            // 처음 19개 라운드 정답 처리
            for (int i = 1; i <= 19; i++) {
                final int roundNum = i;
                GameRound round = session.getRounds().stream()
                        .filter(r -> r.getRoundNumber() == roundNum)
                        .findFirst()
                        .orElseThrow();
                fanChallengeService.processAnswer(session.getId(), roundNum, round.getSong().getTitle(), 5000);
            }

            // When: 마지막 라운드(20번) 오답
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 20, "틀린 답", 5000);

            // Then: 게임 완료 but 퍼펙트 아님
            assertThat(result.isGameOver()).isTrue();
            assertThat(result.correctCount()).isEqualTo(19);
            assertThat(result.remainingLives()).isEqualTo(2);
        }
    }

    // =====================================================
    // Edge Case 10: 정답 변형 시나리오
    // =====================================================
    @Nested
    @DisplayName("정답 변형 시나리오")
    class AnswerVariationScenarios {

        @Test
        @DisplayName("대소문자 무시하고 정답 처리")
        void caseInsensitiveAnswer() {
            // Given: 20곡 생성 (Dynamite 포함)
            createSong("Dynamite", "BTS");
            for (int i = 2; i <= 20; i++) {
                createSong("BTS Song " + i, "BTS");
            }
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            // Dynamite가 배정된 라운드 찾기
            int dynamiteRound = session.getRounds().stream()
                    .filter(r -> r.getSong().getTitle().equals("Dynamite"))
                    .findFirst()
                    .map(GameRound::getRoundNumber)
                    .orElseThrow();

            // When: 소문자로 제출
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), dynamiteRound, "dynamite", 5000);

            // Then
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        @DisplayName("앞뒤 공백 무시하고 정답 처리")
        void trimmedAnswer() {
            // Given: 20곡 생성 (Dynamite 포함)
            createSong("Dynamite", "BTS");
            for (int i = 2; i <= 20; i++) {
                createSong("BTS Song " + i, "BTS");
            }
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            // Dynamite가 배정된 라운드 찾기
            int dynamiteRound = session.getRounds().stream()
                    .filter(r -> r.getSong().getTitle().equals("Dynamite"))
                    .findFirst()
                    .map(GameRound::getRoundNumber)
                    .orElseThrow();

            // When: 앞뒤 공백 포함
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), dynamiteRound, "  Dynamite  ", 5000);

            // Then
            assertThat(result.isCorrect()).isTrue();
        }
    }

    // =====================================================
    // Edge Case 11: 배치 후 유효 곡 수 감소 시나리오
    // =====================================================
    @Nested
    @DisplayName("배치 후 유효 곡 수 감소 시나리오")
    class SongDeactivationScenarios {

        @Test
        @DisplayName("22곡 아티스트에서 3곡 비활성화 → 19곡 → 시작 불가")
        void artistDropsBelowThreshold_afterDeactivation() {
            // Given: 22곡 생성
            for (int i = 1; i <= 22; i++) {
                createSong("Song " + i, "Deactivation Artist");
            }

            // 배치 시뮬레이션: 3곡 비활성화 (YouTube 검증 실패 등)
            List<Song> songs = songRepository.findAll().stream()
                    .filter(s -> s.getArtist().equals("Deactivation Artist"))
                    .limit(3)
                    .toList();
            for (Song song : songs) {
                song.setUseYn("N");
                songRepository.save(song);
            }

            // When & Then: 19곡만 남아 시작 불가
            assertThatThrownBy(() -> fanChallengeService.startChallenge(
                    null, "테스터", "Deactivation Artist", FanChallengeDifficulty.HARDCORE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다");
        }

        @Test
        @DisplayName("정확히 20곡에서 1곡 비활성화 → 19곡 → 시작 불가")
        void exactly20Songs_oneDeactivated_cannotStart() {
            // Given: 정확히 20곡 생성
            for (int i = 1; i <= 20; i++) {
                createSong("Song " + i, "Exact20 Artist");
            }

            // 1곡 비활성화
            Song songToDeactivate = songRepository.findAll().stream()
                    .filter(s -> s.getArtist().equals("Exact20 Artist"))
                    .findFirst()
                    .orElseThrow();
            songToDeactivate.setUseYn("N");
            songRepository.save(songToDeactivate);

            // When & Then: 19곡만 남아 시작 불가
            assertThatThrownBy(() -> fanChallengeService.startChallenge(
                    null, "테스터", "Exact20 Artist", FanChallengeDifficulty.HARDCORE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다");
        }

        @Test
        @DisplayName("25곡에서 5곡 비활성화 → 20곡 → 시작 가능")
        void artistStaysAtThreshold_afterDeactivation() {
            // Given: 25곡 생성
            for (int i = 1; i <= 25; i++) {
                createSong("Song " + i, "Threshold Artist");
            }

            // 5곡 비활성화
            List<Song> songsToDeactivate = songRepository.findAll().stream()
                    .filter(s -> s.getArtist().equals("Threshold Artist"))
                    .limit(5)
                    .toList();
            for (Song song : songsToDeactivate) {
                song.setUseYn("N");
                songRepository.save(song);
            }

            // When: 정확히 20곡 남음 → 시작 가능
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "Threshold Artist", FanChallengeDifficulty.HARDCORE);

            // Then
            assertThat(session).isNotNull();
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }

        @Test
        @DisplayName("게임 시작 후 곡 비활성화 → 진행 중인 게임에 영향 없음")
        void deactivationDuringGame_noEffect() {
            // Given: 20곡 생성 및 게임 시작
            for (int i = 1; i <= 20; i++) {
                createSong("Song " + i, "MidGame Artist");
            }
            GameSession session = fanChallengeService.startChallenge(
                    null, "테스터", "MidGame Artist", FanChallengeDifficulty.HARDCORE);

            // 게임 도중 10곡 비활성화 (배치 실행 시뮬레이션)
            List<Song> songsToDeactivate = songRepository.findAll().stream()
                    .filter(s -> s.getArtist().equals("MidGame Artist"))
                    .limit(10)
                    .toList();
            for (Song song : songsToDeactivate) {
                song.setUseYn("N");
                songRepository.save(song);
            }

            // When: 진행 중인 게임에서 정답 제출
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, "Song 1", 5000);

            // Then: 게임은 정상 진행 (이미 시작된 세션은 영향 없음)
            assertThat(result.isGameOver()).isFalse();
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }

        @Test
        @DisplayName("Soft Delete된 곡은 유효 곡 수에서 제외")
        void softDeletedSongs_excludedFromCount() {
            // Given: 22곡 생성
            for (int i = 1; i <= 22; i++) {
                createSong("Song " + i, "SoftDelete Artist");
            }

            // 3곡 Soft Delete (songService.softDeleteSong 사용)
            List<Song> songsToDelete = songRepository.findAll().stream()
                    .filter(s -> s.getArtist().equals("SoftDelete Artist"))
                    .limit(3)
                    .toList();
            for (Song song : songsToDelete) {
                songService.softDeleteSong(song.getId());
            }

            // When & Then: 19곡만 남아 시작 불가
            assertThatThrownBy(() -> fanChallengeService.startChallenge(
                    null, "테스터", "SoftDelete Artist", FanChallengeDifficulty.HARDCORE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다");
        }
    }

    // =====================================================
    // Edge Case 12: 시간 기록 시나리오
    // =====================================================
    @Nested
    @DisplayName("시간 기록 시나리오")
    class TimeRecordingScenarios {

        @Autowired
        private com.kh.game.repository.FanChallengeRecordRepository fanChallengeRecordRepository;

        @Autowired
        private com.kh.game.repository.MemberRepository memberRepository;

        @Autowired
        private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

        private Member testMember;

        @BeforeEach
        void setUpMember() {
            // 테스트 회원 생성
            testMember = memberRepository.findByEmail("time-test@test.com").orElseGet(() -> {
                Member m = new Member();
                m.setEmail("time-test@test.com");
                m.setPassword(passwordEncoder.encode("1234"));
                m.setNickname("시간테스터");
                m.setUsername("timetester");
                m.setRole(Member.MemberRole.USER);
                m.setStatus(Member.MemberStatus.ACTIVE);
                return memberRepository.save(m);
            });
        }

        @Test
        @DisplayName("퍼펙트가 아니어도 시간이 기록되어야 함")
        void nonPerfect_shouldRecordTime() {
            // Given: 20곡 생성 및 게임 시작
            createSongsForArtist("Time Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "시간테스터", "Time Test Artist", FanChallengeDifficulty.HARDCORE);

            // 17개 정답, 3개 오답 (퍼펙트 아님)
            for (int i = 1; i <= 17; i++) {
                final int roundNum = i;
                GameRound round = session.getRounds().stream()
                        .filter(r -> r.getRoundNumber() == roundNum)
                        .findFirst()
                        .orElseThrow();
                fanChallengeService.processAnswer(session.getId(), roundNum, round.getSong().getTitle(), 5000);
            }
            for (int i = 18; i <= 20; i++) {
                fanChallengeService.processAnswer(session.getId(), i, "틀린 답", 5000);
            }

            // When: 기록 업데이트
            fanChallengeService.updateRecord(session, FanChallengeDifficulty.HARDCORE);

            // Then: 시간이 기록되어야 함
            FanChallengeRecord record = fanChallengeRecordRepository
                    .findByMemberAndArtistAndDifficulty(testMember, "Time Test Artist", FanChallengeDifficulty.HARDCORE)
                    .orElseThrow();

            assertThat(record.getCorrectCount()).isEqualTo(17);
            assertThat(record.getIsPerfectClear()).isFalse();
            assertThat(record.getBestTimeMs()).isNotNull();  // 시간이 기록됨!
        }

        @Test
        @DisplayName("동점일 때 시간이 더 빠르면 갱신되어야 함")
        void sameScore_fasterTime_shouldUpdate() {
            // Given: 기록 직접 생성 (첫 번째: 15개 정답, 100초)
            FanChallengeRecord record = new FanChallengeRecord(testMember, "Speed Test Artist", 20, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(15);
            record.setBestTimeMs(100000L);  // 100초
            record.setAchievedAt(java.time.LocalDateTime.now());
            fanChallengeRecordRepository.save(record);

            Long firstTime = record.getBestTimeMs();

            // When: 같은 15개 정답, 더 빠른 시간 (50초)으로 게임 완료
            createSongsForArtist("Speed Test Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "시간테스터", "Speed Test Artist", FanChallengeDifficulty.HARDCORE);

            // startedAt을 50초 전으로 설정하여 playTimeSeconds가 50이 되도록
            session.setStartedAt(java.time.LocalDateTime.now().minusSeconds(50));
            session.setCorrectCount(15);
            session.setCompletedRounds(20);
            session.setEndedAt(java.time.LocalDateTime.now());
            gameSessionRepository.save(session);

            fanChallengeService.updateRecord(session, FanChallengeDifficulty.HARDCORE);

            // Then: 시간이 갱신되어야 함
            FanChallengeRecord updatedRecord = fanChallengeRecordRepository
                    .findByMemberAndArtistAndDifficulty(testMember, "Speed Test Artist", FanChallengeDifficulty.HARDCORE)
                    .orElseThrow();

            assertThat(updatedRecord.getCorrectCount()).isEqualTo(15);
            assertThat(updatedRecord.getBestTimeMs()).isLessThan(firstTime);  // 더 빠른 시간으로 갱신됨
        }

        @Test
        @DisplayName("더 높은 점수면 시간도 함께 갱신되어야 함")
        void higherScore_shouldUpdateWithTime() {
            // Given: 기록 직접 생성 (첫 번째: 10개 정답, 80초)
            FanChallengeRecord record = new FanChallengeRecord(testMember, "Higher Score Artist", 20, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(10);
            record.setBestTimeMs(80000L);  // 80초
            record.setAchievedAt(java.time.LocalDateTime.now());
            fanChallengeRecordRepository.save(record);

            // When: 더 높은 점수 (15개 정답, 60초)로 게임 완료
            createSongsForArtist("Higher Score Artist", 20);
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "시간테스터", "Higher Score Artist", FanChallengeDifficulty.HARDCORE);

            session.setStartedAt(java.time.LocalDateTime.now().minusSeconds(60));
            session.setCorrectCount(15);
            session.setCompletedRounds(20);
            session.setEndedAt(java.time.LocalDateTime.now());
            gameSessionRepository.save(session);

            fanChallengeService.updateRecord(session, FanChallengeDifficulty.HARDCORE);

            // Then: 점수와 시간 모두 갱신
            FanChallengeRecord updatedRecord = fanChallengeRecordRepository
                    .findByMemberAndArtistAndDifficulty(testMember, "Higher Score Artist", FanChallengeDifficulty.HARDCORE)
                    .orElseThrow();

            assertThat(updatedRecord.getCorrectCount()).isEqualTo(15);
            assertThat(updatedRecord.getBestTimeMs()).isEqualTo(60000L);  // 60초
        }
    }
}
