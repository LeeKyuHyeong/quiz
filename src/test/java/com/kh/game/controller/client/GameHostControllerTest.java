package com.kh.game.controller.client;

import com.kh.game.entity.Genre;
import com.kh.game.entity.Song;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("GameHostController 테스트 - 아티스트/연도 필터")
class GameHostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private GenreRepository genreRepository;

    private Genre kpopGenre;

    @BeforeEach
    void setUp() {
        songRepository.deleteAll();
        genreRepository.deleteAll();

        // 장르 생성
        kpopGenre = new Genre();
        kpopGenre.setCode("KPOP");
        kpopGenre.setName("K-POP");
        kpopGenre.setUseYn("Y");
        kpopGenre = genreRepository.save(kpopGenre);

        // 테스트 데이터 생성
        createSong("Dynamite", "BTS", kpopGenre, "Y", false, 2020, "abc123");
        createSong("Butter", "BTS", kpopGenre, "Y", false, 2021, "def456");
        createSong("Permission to Dance", "BTS", kpopGenre, "Y", false, 2021, "ghi789");
        createSong("Next Level", "aespa", kpopGenre, "Y", false, 2021, "jkl012");
        createSong("Savage", "aespa", kpopGenre, "Y", false, 2021, "mno345");
        createSong("IU Song", "IU", kpopGenre, "Y", true, 2019, "pqr678");
        createSong("Old Song", "Old Artist", kpopGenre, "Y", true, 2015, "stu901");
    }

    private Song createSong(String title, String artist, Genre genre, String useYn,
                            Boolean isSolo, Integer releaseYear, String youtubeVideoId) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setGenre(genre);
        song.setUseYn(useYn);
        song.setIsSolo(isSolo);
        song.setReleaseYear(releaseYear);
        song.setYoutubeVideoId(youtubeVideoId);
        return songRepository.save(song);
    }

    @Nested
    @DisplayName("아티스트 API")
    class ArtistApiTests {

        @Test
        @DisplayName("GET /artists - 아티스트 목록 조회 (곡 수 포함)")
        void getArtists_returnsArtistListWithCount() throws Exception {
            mockMvc.perform(get("/game/solo/host/artists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4)))) // BTS, aespa, IU, Old Artist
                .andExpect(jsonPath("$[?(@.name == 'BTS')].count", hasItem(3)))
                .andExpect(jsonPath("$[?(@.name == 'aespa')].count", hasItem(2)));
        }

        @Test
        @DisplayName("GET /artists/search - 아티스트 검색 (키워드 일치)")
        void searchArtists_returnsMatchingArtists() throws Exception {
            mockMvc.perform(get("/game/solo/host/artists/search")
                    .param("keyword", "BTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasItem("BTS")));
        }

        @Test
        @DisplayName("GET /artists/search - 부분 키워드 검색")
        void searchArtists_partialKeyword_returnsMatching() throws Exception {
            mockMvc.perform(get("/game/solo/host/artists/search")
                    .param("keyword", "ae"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasItem("aespa")));
        }
    }

    @Nested
    @DisplayName("연도 API")
    class YearApiTests {

        @Test
        @DisplayName("GET /years - 연도 목록 조회 (곡 수 포함)")
        void getYears_returnsYearListWithCount() throws Exception {
            mockMvc.perform(get("/game/solo/host/years"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4)))) // 2015, 2019, 2020, 2021
                .andExpect(jsonPath("$[?(@.year == 2021)].count", hasItem(4)));
        }
    }

    @Nested
    @DisplayName("곡 수 조회 API (POST)")
    class SongCountApiTests {

        @Test
        @DisplayName("POST /song-count - 아티스트 필터로 곡 수 조회")
        void getSongCountPost_withArtists_returnsFilteredCount() throws Exception {
            String requestBody = """
                {
                    "artists": ["BTS"]
                }
                """;

            mockMvc.perform(post("/game/solo/host/song-count")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3)); // BTS 곡 3개
        }

        @Test
        @DisplayName("POST /song-count - 복수 아티스트 필터로 곡 수 조회")
        void getSongCountPost_withMultipleArtists_returnsFilteredCount() throws Exception {
            String requestBody = """
                {
                    "artists": ["BTS", "aespa"]
                }
                """;

            mockMvc.perform(post("/game/solo/host/song-count")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5)); // BTS 3개 + aespa 2개
        }

        @Test
        @DisplayName("POST /song-count - 연도 필터로 곡 수 조회")
        void getSongCountPost_withYears_returnsFilteredCount() throws Exception {
            String requestBody = """
                {
                    "years": [2021]
                }
                """;

            mockMvc.perform(post("/game/solo/host/song-count")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4)); // 2021년 곡 4개
        }

        @Test
        @DisplayName("POST /song-count - 복수 연도 필터로 곡 수 조회")
        void getSongCountPost_withMultipleYears_returnsFilteredCount() throws Exception {
            String requestBody = """
                {
                    "years": [2020, 2021]
                }
                """;

            mockMvc.perform(post("/game/solo/host/song-count")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5)); // 2020년 1개 + 2021년 4개
        }

        @Test
        @DisplayName("POST /song-count - 아티스트 + 연도 복합 필터")
        void getSongCountPost_withArtistsAndYears_returnsFilteredCount() throws Exception {
            String requestBody = """
                {
                    "artists": ["BTS"],
                    "years": [2021]
                }
                """;

            mockMvc.perform(post("/game/solo/host/song-count")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2)); // BTS의 2021년 곡 2개
        }
    }

    @Nested
    @DisplayName("게임 시작 API")
    class GameStartApiTests {

        @Test
        @DisplayName("POST /start - FIXED_ARTIST 모드로 게임 시작")
        void startGame_fixedArtistMode_createsSession() throws Exception {
            String requestBody = """
                {
                    "players": ["Player1", "Player2"],
                    "totalRounds": 3,
                    "gameMode": "FIXED_ARTIST",
                    "settings": {
                        "selectedArtists": ["BTS"]
                    }
                }
                """;

            mockMvc.perform(post("/game/solo/host/start")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.gameMode").value("FIXED_ARTIST"));
        }

        @Test
        @DisplayName("POST /start - FIXED_YEAR 모드로 게임 시작")
        void startGame_fixedYearMode_createsSession() throws Exception {
            String requestBody = """
                {
                    "players": ["Player1", "Player2"],
                    "totalRounds": 3,
                    "gameMode": "FIXED_YEAR",
                    "settings": {
                        "selectedYears": [2021]
                    }
                }
                """;

            mockMvc.perform(post("/game/solo/host/start")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.gameMode").value("FIXED_YEAR"));
        }
    }
}
