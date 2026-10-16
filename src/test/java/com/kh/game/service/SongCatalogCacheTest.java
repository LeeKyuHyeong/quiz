package com.kh.game.service;

import com.kh.game.batch.DuplicateSongCheckBatch;
import com.kh.game.entity.BatchExecutionHistory;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Song;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.SongRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게임 설정 화면이 매 요청마다 활성 곡 전체를 읽어 자바에서 집계하던 것(아티스트·연도)과
 * 게임용 장르 목록을 캐시한다 (career 【확인 46】).
 *
 * 정확성은 쓰기 경로의 무효화에 달려 있으므로 이 테스트의 핵심은 "관리자·배치가 곡/장르를 바꾼 뒤
 * 게임이 옛 값을 보지 않는다" 이다. @Transactional 을 붙이지 않아 쓰기가 실제로 커밋된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("곡 집계·장르 목록 캐시 — 적중·무효화·불변")
class SongCatalogCacheTest {

    @Autowired private SongService songService;
    @Autowired private GenreService genreService;
    @Autowired private SongRepository songRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private DuplicateSongCheckBatch duplicateSongCheckBatch;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    // youtube_video_id 는 20자 제한 — "yt-<8자리>-1" 로 맞춘다
    private final String marker = String.valueOf(System.nanoTime() % 100_000_000L);
    private final List<Long> songIds = new ArrayList<>();
    private Genre genre;
    private Statistics statistics;

    @BeforeEach
    void seed() {
        genre = new Genre();
        genre.setCode("G" + marker);
        genre.setName("장르 " + marker);
        genre.setUseYn("Y");
        genre.setDisplayOrder(999);
        genre = genreRepository.save(genre);

        songIds.add(createSong("곡1", "BTS-" + marker, "yt-" + marker + "-1").getId());
        songIds.add(createSong("곡2", "BTS-" + marker, "yt-" + marker + "-2").getId());
        songIds.add(createSong("곡3", "aespa-" + marker, "yt-" + marker + "-3").getId());

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @AfterEach
    void cleanUp() {
        statistics.setStatisticsEnabled(false);
        // 중복 곡 검사 배치가 남긴 영향 곡 기록(FK song_id)을 먼저 지운다
        for (Long id : songIds) {
            jdbcTemplate.update("DELETE FROM batch_affected_song WHERE song_id = ?", id);
        }
        songRepository.findAllById(songIds).forEach(songRepository::delete);
        songRepository.findAll().stream()
                .filter(s -> s.getArtist() != null && s.getArtist().endsWith(marker))
                .forEach(songRepository::delete);
        genreRepository.findById(genre.getId()).ifPresent(genreRepository::delete);
    }

    private Song createSong(String title, String artist, String youtubeId) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setGenre(genre);
        song.setYoutubeVideoId(youtubeId);
        song.setUseYn("Y");
        song.setIsPopular(true);
        song.setIsSolo(false);
        song.setReleaseYear(2020);
        return songRepository.save(song);
    }

    private int artistCount(String artist) {
        return songService.getArtistsWithCount().stream()
                .filter(a -> artist.equals(a.get("name")))
                .map(a -> ((Number) a.get("count")).intValue())
                .findFirst().orElse(0);
    }

    @Nested
    @DisplayName("적중 — 두 번째 호출은 DB 를 읽지 않는다")
    class Hit {

        @Test
        @DisplayName("아티스트 집계: 같은 결과를 두 번 요청하면 쿼리는 1회")
        void artistsWithCount_secondCallHitsCache() {
            songService.getArtistsWithCount();
            long afterFirst = statistics.getQueryExecutionCount();
            songService.getArtistsWithCount();
            assertThat(afterFirst).isEqualTo(1);
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("연도 집계·게임용 장르 목록도 같다")
        void yearsAndGenres_secondCallHitsCache() {
            songService.getYearsWithCount();
            genreService.findActiveGenresForGame();
            long afterFirst = statistics.getQueryExecutionCount();
            songService.getYearsWithCount();
            genreService.findActiveGenresForGame();
            assertThat(afterFirst).isEqualTo(2);
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(afterFirst);
        }
    }

    @Nested
    @DisplayName("무효화 — 바꾼 뒤 게임이 옛 값을 보지 않는다")
    class Invalidation {

        @Test
        @DisplayName("관리자가 곡을 비활성화하면 아티스트 곡 수가 줄어든다")
        void adminToggleUseYn_evictsArtists() {
            assertThat(artistCount("BTS-" + marker)).isEqualTo(2);
            songService.toggleUseYn(songIds.get(0));
            assertThat(artistCount("BTS-" + marker)).isEqualTo(1);
        }

        @Test
        @DisplayName("관리자가 곡을 소프트 삭제·복구하면 연도 곡 수가 따라간다")
        void softDeleteAndRestore_evictsYears() {
            int before = yearCount(2020);
            songService.softDeleteSong(songIds.get(2));
            assertThat(yearCount(2020)).isEqualTo(before - 1);
            songService.restoreSong(songIds.get(2));
            assertThat(yearCount(2020)).isEqualTo(before);
        }

        @Test
        @DisplayName("관리자가 장르를 비활성화하면 게임용 장르 목록에서 빠진다")
        void genreToggle_evictsGenres() {
            assertThat(genreService.findActiveGenresForGame()).extracting(Genre::getId).contains(genre.getId());
            genreService.toggleUseYn(genre.getId());
            assertThat(genreService.findActiveGenresForGame()).extracting(Genre::getId).doesNotContain(genre.getId());
        }

        @Test
        @DisplayName("중복 곡 검사 배치가 곡을 비활성화해도 새 값을 본다")
        void duplicateBatch_evictsArtists() {
            Long duplicateId = createSong("곡1 복제", "BTS-" + marker, "yt-" + marker + "-1").getId();
            songIds.add(duplicateId);
            assertThat(artistCount("BTS-" + marker)).isEqualTo(3);

            duplicateSongCheckBatch.execute(BatchExecutionHistory.ExecutionType.MANUAL);

            assertThat(songRepository.findById(duplicateId).orElseThrow().getUseYn()).isEqualTo("N");
            assertThat(artistCount("BTS-" + marker)).isEqualTo(2);
        }

        private int yearCount(int year) {
            return songService.getYearsWithCount().stream()
                    .filter(y -> Integer.valueOf(year).equals(y.get("year")))
                    .map(y -> ((Number) y.get("count")).intValue())
                    .findFirst().orElse(0);
        }
    }

    @Nested
    @DisplayName("불변 — 캐시된 목록을 호출자가 바꿀 수 없다")
    class Immutable {

        @Test
        @DisplayName("목록·항목 모두 수정 시 UnsupportedOperationException")
        void cachedCollectionsAreUnmodifiable() {
            List<Map<String, Object>> artists = songService.getArtistsWithCountForFanChallenge();
            assertThatThrownBy(() -> artists.remove(0)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> artists.get(0).put("count", 0)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> genreService.findActiveGenresForGame().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("팬 챌린지 아티스트 API 는 캐시를 건드리지 않고 걸러서 준다")
        void fanChallengeArtistsEndpoint_filtersCopy() throws Exception {
            // 시드 아티스트는 곡 수가 기준(20) 미만이라 응답에서 빠져야 하고,
            // 그 뒤에도 캐시 원본에는 남아 있어야 한다
            mockMvc.perform(get("/game/fan-challenge/artists"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == 'BTS-" + marker + "')]").isEmpty());
            assertThat(songService.getArtistsWithCountForFanChallenge())
                    .extracting(a -> a.get("name")).contains("BTS-" + marker);
        }
    }
}
