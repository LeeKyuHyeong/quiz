package com.kh.game.controller.admin;

import com.kh.game.entity.Genre;
import com.kh.game.entity.Song;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
@DisplayName("AdminSongController 테스트")
class AdminSongControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private GenreRepository genreRepository;

    private MockHttpSession adminSession;
    private Genre kpopGenre;

    @BeforeEach
    void setUp() {
        songRepository.deleteAll();
        genreRepository.deleteAll();

        // 관리자 세션 설정
        adminSession = new MockHttpSession();
        adminSession.setAttribute("admin", true);

        // 장르 생성
        kpopGenre = new Genre();
        kpopGenre.setCode("KPOP");
        kpopGenre.setName("K-POP");
        kpopGenre.setUseYn("Y");
        kpopGenre = genreRepository.save(kpopGenre);

        // 테스트 데이터 생성
        createSong("Dynamite", "BTS", kpopGenre, "Y", true, 2020, "abc123");
        createSong("Butter", "BTS", kpopGenre, "Y", false, 2021, "def456");
        createSong("Next Level", "aespa", kpopGenre, "Y", false, 2021, "ghi789");
        createSong("Inactive Song", "Test Artist", null, "N", true, 2019, null);
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

    @Test
    @DisplayName("노래 목록 조회 - 파라미터 없이 접근하면 전체 조회")
    void list_noParams_returnsAll() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 4L))
            .andExpect(model().attribute("songs", hasSize(4)));
    }

    @Test
    @DisplayName("노래 목록 조회 - 빈 문자열 파라미터로 검색해도 전체 조회 (버그 수정 검증)")
    void list_emptyStringParams_returnsAll() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("keyword", "")
                .param("useYn", "")
                .param("genreId", ""))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 4L))
            .andExpect(model().attribute("songs", hasSize(4)));
    }

    @Test
    @DisplayName("노래 목록 조회 - keyword로 제목 검색")
    void list_searchByKeyword_title() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("keyword", "Dynamite"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 1L))
            .andExpect(model().attribute("songs", hasSize(1)));
    }

    @Test
    @DisplayName("노래 목록 조회 - keyword로 아티스트 검색")
    void list_searchByKeyword_artist() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("keyword", "BTS"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L))
            .andExpect(model().attribute("songs", hasSize(2)));
    }

    @Test
    @DisplayName("노래 목록 조회 - 사용여부 필터 (사용)")
    void list_filterByUseYn_active() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("useYn", "Y"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 3L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 사용여부 필터 (미사용)")
    void list_filterByUseYn_inactive() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("useYn", "N"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 1L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 장르 필터")
    void list_filterByGenre() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("genreId", kpopGenre.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 3L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 솔로 필터")
    void list_filterByIsSolo() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("isSolo", "true"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 그룹 필터")
    void list_filterByIsGroup() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("isSolo", "false"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 단일 아티스트 필터")
    void list_filterBySingleArtist() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("artists", "BTS"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 다중 아티스트 필터")
    void list_filterByMultipleArtists() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("artists", "BTS")
                .param("artists", "aespa"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 3L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 복합 조건 (keyword + useYn + isSolo)")
    void list_multipleFilters() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("keyword", "")
                .param("useYn", "Y")
                .param("isSolo", "false"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 정렬 (제목 오름차순)")
    void list_sortByTitle_asc() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("sort", "title")
                .param("direction", "asc"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("songs", hasSize(4)));
    }

    @Test
    @DisplayName("노래 목록 조회 - 페이징")
    void list_pagination() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("page", "0")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("songs", hasSize(2)))
            .andExpect(model().attribute("totalItems", 4L))
            .andExpect(model().attribute("totalPages", 2));
    }

    @Test
    @DisplayName("노래 목록 조회 - 뷰 모드 (그리드)")
    void list_viewMode_grid() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("viewMode", "grid"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("viewMode", "grid"));
    }

    @Test
    @DisplayName("노래 목록 조회 - 연도 필터")
    void list_filterByReleaseYear() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession)
                .param("releaseYear", "2021"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalItems", 2L));
    }

    @Test
    @DisplayName("노래 목록 조회 - 연도 목록이 model에 포함됨")
    void list_hasYearsInModel() throws Exception {
        mockMvc.perform(get("/admin/song/content")
                .session(adminSession))
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("years"));
    }
}
