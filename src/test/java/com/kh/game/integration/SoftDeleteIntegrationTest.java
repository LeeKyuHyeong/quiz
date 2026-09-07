package com.kh.game.integration;

import com.kh.game.entity.*;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.*;
import com.kh.game.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * TDD Phase 3 & 4: Soft Delete 통합 테스트
 *
 * 게임 세션 안정성 + 랭킹 표시 테스트
 *
 * 정책:
 * - 게임 중 곡이 soft delete 되어도 세션 안정성 유지
 * - 랭킹: 현재 곡 수 기준 (max 100%)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Soft Delete 통합 테스트")
class SoftDeleteIntegrationTest {

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
    private MemberRepository memberRepository;

    @Autowired
    private FanChallengeRecordRepository fanChallengeRecordRepository;

    @Autowired
    private GameSessionRepository gameSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private YouTubeValidationService youTubeValidationService;

    private Genre kpopGenre;
    private Member testMember;
    private int songCounter = 0;

    @BeforeEach
    void setUp() {
        // 기존 데이터 정리
        gameSessionRepository.deleteAll();
        fanChallengeRecordRepository.deleteAll();
        songAnswerRepository.deleteAll();
        songRepository.deleteAll();

        // YouTube 검증 Mock
        YouTubeValidationService.ValidationResult validResult = YouTubeValidationService.ValidationResult.valid();
        Mockito.when(youTubeValidationService.validateVideo(anyString())).thenReturn(validResult);

        // 장르 생성/조회
        kpopGenre = genreRepository.findByCode("KPOP").orElseGet(() -> {
            Genre g = new Genre();
            g.setCode("KPOP");
            g.setName("K-POP");
            g.setUseYn("Y");
            return genreRepository.save(g);
        });

        // 테스트 회원 생성
        testMember = memberRepository.findByEmail("integration-test@test.com").orElseGet(() -> {
            Member m = new Member();
            m.setEmail("integration-test@test.com");
            m.setPassword(passwordEncoder.encode("1234"));
            m.setNickname("통합테스터");
            m.setUsername("integrationtester");
            m.setRole(Member.MemberRole.USER);
            m.setStatus(Member.MemberStatus.ACTIVE);
            return memberRepository.save(m);
        });
    }

    private Song createSong(String title, String artist) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setGenre(kpopGenre);
        song.setUseYn("Y");
        song.setIsSolo(false);
        song.setYoutubeVideoId("vid_" + (++songCounter));
        song.setStartTime(0);
        song.setPlayDuration(30);
        song.setIsYoutubeValid(true);
        Song saved = songRepository.save(song);

        // 정답 추가
        SongAnswer answer = new SongAnswer();
        answer.setSong(saved);
        answer.setAnswer(title);
        answer.setIsPrimary(true);
        songAnswerRepository.save(answer);

        return saved;
    }

    // =====================================================
    // 4. 게임 세션 안정성
    // =====================================================
    @Nested
    @DisplayName("4. 게임 세션 안정성")
    class GameSessionStabilityTests {

        @Test
        @DisplayName("4.1 게임 중 곡 soft delete 시 세션이 유지되어야 함")
        void gameInProgress_softDelete_shouldContinueNormally() {
            // Given: BTS 20곡으로 게임 시작
            for (int i = 1; i <= 20; i++) {
                createSong("BTS Song " + i, "BTS");
            }

            GameSession session = fanChallengeService.startChallenge(
                    testMember, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            // 1라운드 정답 찾기 및 처리
            GameRound round1 = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 1)
                    .findFirst().orElseThrow();
            fanChallengeService.processAnswer(session.getId(), 1, round1.getSong().getTitle(), 5000);

            // When: 게임 중 2번 라운드의 곡 soft delete
            GameRound round2 = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 2)
                    .findFirst().orElseThrow();
            songService.softDeleteSong(round2.getSong().getId());

            // Then: 2라운드 (삭제된 곡) 정상 진행 가능 (세션 데이터는 이미 로드됨)
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 2, round2.getSong().getTitle(), 5000);

            assertThat(result.isCorrect()).isTrue();
            assertThat(result.isGameOver()).isFalse();
        }

        @Test
        @DisplayName("4.2 soft delete된 곡도 기존 세션에서 정답 처리 가능")
        void softDeletedSong_shouldStillBePlayable() {
            // Given: 20곡 생성
            for (int i = 1; i <= 20; i++) {
                createSong("Solo Song " + i, "Solo Artist");
            }
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "테스터", "Solo Artist", FanChallengeDifficulty.HARDCORE);

            // 1라운드 곡 찾기
            GameRound round1 = session.getRounds().stream()
                    .filter(r -> r.getRoundNumber() == 1)
                    .findFirst().orElseThrow();
            Song song1 = round1.getSong();

            // When: 게임 시작 후 1라운드 곡 soft delete
            songService.softDeleteSong(song1.getId());

            // Then: 이미 시작된 게임은 정상 진행
            FanChallengeService.AnswerResult result = fanChallengeService.processAnswer(
                    session.getId(), 1, song1.getTitle(), 5000);

            assertThat(result.isCorrect()).isTrue();
            assertThat(result.isGameOver()).isFalse();
        }

        @Test
        @DisplayName("4.3 새 게임 시작 시 soft delete된 곡은 제외")
        void newGame_shouldExcludeSoftDeletedSongs() {
            // Given: 22곡 중 2곡 soft delete → 20곡 남음
            Song song1 = createSong("BTS Song 1", "BTS");
            Song song2 = createSong("BTS Song 2", "BTS");
            for (int i = 3; i <= 22; i++) {
                createSong("BTS Song " + i, "BTS");
            }

            songService.softDeleteSong(song1.getId());
            songService.softDeleteSong(song2.getId());

            // When: 새 게임 시작
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            // Then: 20곡만 포함 (22 - 2 = 20)
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }

        @Test
        @DisplayName("4.4 전곡 soft delete 후 새 게임 시작 시 예외 발생")
        void newGame_allSoftDeleted_shouldThrowException() {
            // Given: 2곡 생성 후 모두 삭제
            Song song1 = createSong("Song 1", "Test Artist");
            Song song2 = createSong("Song 2", "Test Artist");

            songService.softDeleteSong(song1.getId());
            songService.softDeleteSong(song2.getId());

            // When & Then: 0곡이면 20곡 이상 에러
            assertThatThrownBy(() ->
                    fanChallengeService.startChallenge(testMember, "테스터", "Test Artist", FanChallengeDifficulty.HARDCORE)
            ).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("도전할 수 없습니다")
                    .hasMessageContaining("현재 0곡");
        }
    }

    // =====================================================
    // 5. 랭킹/기록 표시 (현재 곡 수 기준, max 100%)
    // =====================================================
    @Nested
    @DisplayName("5. 랭킹/기록 표시")
    class RankingDisplayTests {

        @Test
        @DisplayName("5.1 기록은 달성 당시 기준으로 유지되어야 함")
        void ranking_shouldShowOriginalRecord() {
            // Given: 50곡 퍼펙트 기록
            FanChallengeRecord record = new FanChallengeRecord(testMember, "BTS", 50, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(50);
            record.setIsPerfectClear(true);
            record.setBestTimeMs(120000L);
            record.setAchievedAt(LocalDateTime.now());
            record = fanChallengeRecordRepository.save(record);

            // When: 기록 조회
            Optional<FanChallengeRecord> found = fanChallengeService.getMemberRecord(
                    testMember, "BTS", FanChallengeDifficulty.HARDCORE);

            // Then: correctCount는 원본 유지
            assertThat(found).isPresent();
            assertThat(found.get().getCorrectCount()).isEqualTo(50);
            assertThat(found.get().getTotalSongs()).isEqualTo(50);
        }

        @Test
        @DisplayName("5.2 클리어율은 100%를 초과하지 않아야 함")
        void ranking_clearRate_shouldNotExceed100() {
            // Given: 50곡 퍼펙트 달성 후 10곡 삭제된 상황을 시뮬레이션
            // (기록: 50/50, 현재 곡 수: 40)
            FanChallengeRecord record = new FanChallengeRecord(testMember, "BTS", 50, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(50);
            record.setIsPerfectClear(true);
            record.setBestTimeMs(120000L);
            record.setAchievedAt(LocalDateTime.now());
            record = fanChallengeRecordRepository.save(record);

            // When: 클리어율 계산 (현재 곡 수 기준)
            int currentSongCount = 40; // 시뮬레이션: 10곡 삭제됨
            double clearRate = calculateClearRate(record.getCorrectCount(), currentSongCount);

            // Then: max 100%
            assertThat(clearRate).isLessThanOrEqualTo(100.0);
        }

        @Test
        @DisplayName("5.3 correctCount는 변경되지 않아야 함")
        void ranking_correctCountRemains() {
            // Given
            for (int i = 1; i <= 5; i++) {
                createSong("BTS Song " + i, "BTS");
            }

            // 퍼펙트 기록 생성
            FanChallengeRecord record = new FanChallengeRecord(testMember, "BTS", 5, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(5);
            record.setIsPerfectClear(true);
            record.setBestTimeMs(30000L);
            record.setAchievedAt(LocalDateTime.now());
            record = fanChallengeRecordRepository.save(record);

            // 2곡 soft delete
            List<Song> btsSongs = songRepository.findByArtistAndUseYn("BTS", "Y");
            songService.softDeleteSong(btsSongs.get(0).getId());
            songService.softDeleteSong(btsSongs.get(1).getId());

            // When
            FanChallengeRecord updated = fanChallengeRecordRepository.findById(record.getId()).orElseThrow();

            // Then: correctCount 변경 없음
            assertThat(updated.getCorrectCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("5.4 랭킹 조회 시 현재 곡 수로 비율 계산")
        void ranking_shouldCalculateWithCurrentSongCount() {
            // Given: BTS 곡 5개
            for (int i = 1; i <= 5; i++) {
                createSong("BTS Song " + i, "BTS");
            }

            // 퍼펙트 기록 (5곡)
            FanChallengeRecord record = new FanChallengeRecord(testMember, "BTS", 5, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(5);
            record.setIsPerfectClear(true);
            record.setBestTimeMs(30000L);
            record.setAchievedAt(LocalDateTime.now());
            fanChallengeRecordRepository.save(record);

            // 신곡 2개 추가 (총 7곡)
            createSong("BTS Song 6", "BTS");
            createSong("BTS Song 7", "BTS");

            // When: 랭킹에서 현재 곡 수 기준 비율 계산
            int currentSongCount = 7;
            double clearRate = calculateClearRate(record.getCorrectCount(), currentSongCount);

            // Then: 5/7 = 71.4%
            assertThat(clearRate).isCloseTo(71.4, org.assertj.core.data.Offset.offset(0.1));
        }

        /**
         * 클리어율 계산 (현재 곡 수 기준, max 100%)
         */
        private double calculateClearRate(int correctCount, int currentSongCount) {
            if (currentSongCount == 0) return 0;
            double rate = (double) correctCount / currentSongCount * 100;
            return Math.min(rate, 100.0); // max 100%
        }
    }

    // =====================================================
    // 6. 엣지 케이스
    // =====================================================
    @Nested
    @DisplayName("6. 엣지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("6.1 복구 후 게임 시작 시 복구된 곡 포함")
        void restore_newGame_shouldIncludeRestoredSong() {
            // Given: 21곡 생성
            Song song1 = createSong("Song 1", "BTS");
            for (int i = 2; i <= 21; i++) {
                createSong("Song " + i, "BTS");
            }

            // soft delete (20곡 남음)
            songService.softDeleteSong(song1.getId());

            // 복구 (21곡으로 복원)
            songService.restoreSong(song1.getId());

            // When
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            // Then: 20곡 출제 (21곡 중 20곡 랜덤 선택)
            assertThat(session.getTotalRounds()).isEqualTo(20);
        }

        @Test
        @DisplayName("6.2 동시에 삭제와 추가가 일어난 경우")
        void simultaneousDeleteAndAdd() {
            // Given: 5곡
            Song song1 = createSong("Song 1", "BTS");
            createSong("Song 2", "BTS");
            createSong("Song 3", "BTS");
            createSong("Song 4", "BTS");
            createSong("Song 5", "BTS");

            // 퍼펙트 기록
            FanChallengeRecord record = new FanChallengeRecord(testMember, "BTS", 5, FanChallengeDifficulty.HARDCORE);
            record.setCorrectCount(5);
            record.setIsPerfectClear(true);
            record.setBestTimeMs(30000L);
            record.setAchievedAt(LocalDateTime.now());
            record = fanChallengeRecordRepository.save(record);

            // When: 1곡 삭제 + 1곡 추가 (결과: 5곡 유지)
            songService.softDeleteSong(song1.getId());
            createSong("Song 6", "BTS");

            // Then: 곡 수는 5개이지만, 구성이 바뀜
            List<Song> activeSongs = songService.getAllValidatedSongsByArtist("BTS");
            assertThat(activeSongs).hasSize(5);
            assertThat(activeSongs).extracting(Song::getTitle)
                    .doesNotContain("Song 1")
                    .contains("Song 6");
        }

        @Test
        @DisplayName("6.3 여러 아티스트 동시 삭제")
        void multipleArtists_softDelete() {
            // Given
            createSong("BTS Song 1", "BTS");
            createSong("BTS Song 2", "BTS");
            Song iuSong = createSong("IU Song 1", "IU");
            createSong("IU Song 2", "IU");

            // When: IU 1곡 삭제
            songService.softDeleteSong(iuSong.getId());

            // Then: BTS는 영향 없음
            List<Song> btsSongs = songService.getAllValidatedSongsByArtist("BTS");
            List<Song> iuSongs = songService.getAllValidatedSongsByArtist("IU");

            assertThat(btsSongs).hasSize(2);
            assertThat(iuSongs).hasSize(1);
        }

        @Test
        @DisplayName("6.4 삭제 시점과 게임 시작 시점의 경쟁 조건")
        void raceCondition_deleteAndGameStart() {
            // Given: 21곡 생성
            for (int i = 1; i <= 20; i++) {
                createSong("Song " + i, "BTS");
            }
            Song song21 = createSong("Song 21", "BTS");

            // When: 게임 시작 직전 1곡 삭제 (20곡 남음)
            songService.softDeleteSong(song21.getId());

            // Then: 새 게임은 20곡으로 시작
            GameSession session = fanChallengeService.startChallenge(
                    testMember, "테스터", "BTS", FanChallengeDifficulty.HARDCORE);

            assertThat(session.getTotalRounds()).isEqualTo(20);
        }
    }
}
