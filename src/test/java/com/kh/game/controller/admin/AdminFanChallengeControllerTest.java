package com.kh.game.controller.admin;

import com.kh.game.entity.*;
import com.kh.game.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
@DisplayName("AdminFanChallengeController 테스트")
class AdminFanChallengeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FanChallengeRecordRepository fanChallengeRecordRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession adminSession;
    private Member testMember;
    private Genre kpopGenre;
    private int songCounter = 0;

    @BeforeEach
    void setUp() {
        fanChallengeRecordRepository.deleteAll();
        songRepository.deleteAll();

        // 관리자 세션 설정
        adminSession = new MockHttpSession();
        adminSession.setAttribute("admin", true);

        // 테스트 회원 생성
        testMember = memberRepository.findByEmail("admin-fc-test@test.com").orElseGet(() -> {
            Member m = new Member();
            m.setEmail("admin-fc-test@test.com");
            m.setPassword(passwordEncoder.encode("1234"));
            m.setNickname("팬챌관리테스터");
            m.setUsername("adminfctester");
            m.setRole(Member.MemberRole.USER);
            m.setStatus(Member.MemberStatus.ACTIVE);
            return memberRepository.save(m);
        });

        // 장르 생성
        kpopGenre = genreRepository.findByCode("KPOP").orElseGet(() -> {
            Genre g = new Genre();
            g.setCode("KPOP");
            g.setName("K-POP");
            g.setUseYn("Y");
            return genreRepository.save(g);
        });
    }

    @Test
    @DisplayName("목록 조회 - 기본")
    void list_shouldReturnRecords() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fan-challenge/list"))
                .andExpect(model().attributeExists("records"))
                .andExpect(model().attributeExists("totalCount"))
                .andExpect(model().attributeExists("perfectCount"));
    }

    @Test
    @DisplayName("목록 조회 - 아티스트 필터")
    void list_withArtistFilter_shouldReturnFilteredRecords() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge")
                        .session(adminSession)
                        .param("keyword", "BTS"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fan-challenge/list"))
                .andExpect(model().attribute("keyword", "BTS"));
    }

    @Test
    @DisplayName("목록 조회 - 난이도 필터")
    void list_withDifficultyFilter_shouldReturnFilteredRecords() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge")
                        .session(adminSession)
                        .param("difficulty", "HARDCORE"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fan-challenge/list"))
                .andExpect(model().attribute("difficulty", "HARDCORE"));
    }

    @Test
    @DisplayName("목록 조회 - 퍼펙트 필터")
    void list_withPerfectFilter_shouldReturnFilteredRecords() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge")
                        .session(adminSession)
                        .param("perfect", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fan-challenge/list"))
                .andExpect(model().attribute("perfect", "true"));
    }

    @Test
    @DisplayName("상세 조회 - 성공")
    void detail_shouldReturnRecordDetails() throws Exception {
        // Given
        FanChallengeRecord record = createRecord("BTS", FanChallengeDifficulty.HARDCORE, true);

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge/detail/" + record.getId())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(record.getId()))
                .andExpect(jsonPath("$.artist").value("BTS"))
                .andExpect(jsonPath("$.difficulty").value("HARDCORE"))
                .andExpect(jsonPath("$.isPerfectClear").value(true));
    }

    @Test
    @DisplayName("상세 조회 - 존재하지 않는 기록")
    void detail_notFound_shouldReturn404() throws Exception {
        // When & Then
        mockMvc.perform(get("/admin/fan-challenge/detail/99999")
                        .session(adminSession))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("아티스트 통계 조회")
    void stats_shouldReturnArtistStatistics() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge/stats")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("회원별 기록 조회")
    void memberRecords_shouldReturnMemberRecords() throws Exception {
        // Given
        createTestRecords();

        // When & Then
        mockMvc.perform(get("/admin/fan-challenge/member/" + testMember.getId())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.totalElements").exists());
    }

    private void createTestRecords() {
        // BTS 곡 생성
        for (int i = 1; i <= 5; i++) {
            createSong("BTS Song " + i, "BTS");
        }

        // IU 곡 생성
        for (int i = 1; i <= 5; i++) {
            createSong("IU Song " + i, "IU");
        }

        // BTS HARDCORE 퍼펙트 기록
        createRecord("BTS", FanChallengeDifficulty.HARDCORE, true);

        // BTS NORMAL 기록
        createRecord("BTS", FanChallengeDifficulty.NORMAL, false);

        // IU HARDCORE 기록
        createRecord("IU", FanChallengeDifficulty.HARDCORE, false);
    }

    private FanChallengeRecord createRecord(String artist, FanChallengeDifficulty difficulty, boolean isPerfect) {
        FanChallengeRecord record = new FanChallengeRecord(testMember, artist, 5, difficulty);
        record.setCorrectCount(isPerfect ? 5 : 3);
        record.setIsPerfectClear(isPerfect);
        record.setIsCurrentPerfect(isPerfect);
        record.setBestTimeMs(isPerfect ? 30000L : null);
        record.setAchievedAt(LocalDateTime.now());
        return fanChallengeRecordRepository.save(record);
    }

    private Song createSong(String title, String artist) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setGenre(kpopGenre);
        song.setUseYn("Y");
        song.setIsSolo(false);
        song.setYoutubeVideoId("vid_" + (++songCounter));
        return songRepository.save(song);
    }
}
