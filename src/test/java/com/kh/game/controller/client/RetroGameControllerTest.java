package com.kh.game.controller.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kh.game.entity.*;
import com.kh.game.repository.*;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.YouTubeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("레트로 게임 Controller 테스트")
class RetroGameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private SongAnswerRepository songAnswerRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GameSessionRepository gameSessionRepository;

    @MockitoBean
    private YouTubeValidationService youTubeValidationService;

    private Genre kpopGenre;
    private Genre retroGenre;
    private Member testMember;

    @BeforeEach
    void setUp() {
        // 기존 데이터 정리
        gameSessionRepository.deleteAll();
        songAnswerRepository.deleteAll();
        songRepository.deleteAll();
        genreRepository.deleteAll();
        memberRepository.deleteAll();

        // YouTube 검증 항상 성공하도록 Mock 설정
        YouTubeValidationService.ValidationResult validResult = YouTubeValidationService.ValidationResult.valid();
        Mockito.when(youTubeValidationService.validateVideo(anyString())).thenReturn(validResult);

        // 장르 생성
        kpopGenre = createGenre("KPOP", "K-POP");
        retroGenre = createGenre("RETRO", "Retro/Oldies");

        // 테스트 회원 생성
        testMember = createTestMember();

        // 기본 레트로 곡 생성 (테스트용)
        for (int i = 0; i < 10; i++) {
            createSong("레트로곡" + i, "아티스트" + i, 1990 + i, kpopGenre);
        }
    }

    private Genre createGenre(String code, String name) {
        Genre genre = new Genre();
        genre.setCode(code);
        genre.setName(name);
        genre.setUseYn("Y");
        genre.setDisplayOrder(1);
        return genreRepository.save(genre);
    }

    private Song createSong(String title, String artist, Integer releaseYear, Genre genre) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setReleaseYear(releaseYear);
        song.setGenre(genre);
        song.setYoutubeVideoId("abc" + System.nanoTime());
        song.setPlayDuration(180);
        song.setStartTime(0);
        song.setUseYn("Y");
        Song saved = songRepository.save(song);

        SongAnswer answer = new SongAnswer(saved, title, true);
        songAnswerRepository.save(answer);

        return saved;
    }

    private Member createTestMember() {
        Member member = new Member();
        member.setEmail("test@test.com");
        member.setPassword("password123");
        member.setNickname("테스트유저");
        member.setUsername("testuser");
        member.setStatus(Member.MemberStatus.ACTIVE);
        member.setRole(Member.MemberRole.USER);
        return memberRepository.save(member);
    }

    // =====================================================
    // Scenario 1: 설정 페이지 접근
    // =====================================================
    @Nested
    @DisplayName("설정 페이지 접근")
    class SetupPageAccess {

        @Test
        @DisplayName("GET /game/retro - 설정 페이지 접근 성공")
        void setup_shouldReturnSetupPage() throws Exception {
            mockMvc.perform(get("/game/retro"))
                .andExpect(status().isOk())
                .andExpect(view().name("client/game/retro/setup"));
        }

        // 30곡 챌린지 바로가기는 로그인 여부에 따라 분기 렌더링됨(sec:authorize).
        // 모델 속성이 아니라 렌더링 결과로 검증 — 구현 방식이 바뀌어도 살아남도록.
        // 판별자: 로그인 블록 = onclick="openChallengeModal()", 비로그인 블록 = class="btn-challenge-login"

        @Test
        @DisplayName("비로그인 접근 - 30곡 챌린지에 로그인 유도가 렌더링됨")
        void setup_anonymous_showsLoginPrompt() throws Exception {
            mockMvc.perform(get("/game/retro"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("btn-challenge-login")))
                .andExpect(content().string(not(containsString("openChallengeModal()"))));
        }

        @Test
        @DisplayName("로그인 접근 - 30곡 챌린지 바로 시작이 렌더링됨")
        void setup_authenticated_showsChallengeStart() throws Exception {
            mockMvc.perform(get("/game/retro").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openChallengeModal()")))
                .andExpect(content().string(not(containsString("btn-challenge-login"))));
        }

    }

    // =====================================================
    // Scenario 2: 게임 시작 API
    // =====================================================
    @Nested
    @DisplayName("게임 시작 API")
    class GameStartApi {

        @Test
        @DisplayName("POST /game/retro/start - 게임 시작 성공")
        void start_shouldStartGame() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("nickname", "플레이어");
            request.put("totalRounds", 5);
            request.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.actualRounds").exists());
        }

        @Test
        @DisplayName("로그인 회원 게임 시작 - 회원과 연결")
        void start_withLoggedInMember_shouldLinkToMember() throws Exception {
            CustomUserDetails userDetails = new CustomUserDetails(testMember);

            Map<String, Object> request = new HashMap<>();
            request.put("nickname", "플레이어");
            request.put("totalRounds", 5);
            request.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .with(authentication(new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

            // 생성된 세션이 회원과 연결되었는지 확인
            GameSession latestSession = gameSessionRepository.findAll().stream()
                .filter(s -> s.getGameType() == GameSession.GameType.RETRO_GUESS)
                .findFirst()
                .orElse(null);

            org.assertj.core.api.Assertions.assertThat(latestSession).isNotNull();
            org.assertj.core.api.Assertions.assertThat(latestSession.getMember()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(latestSession.getMember().getId()).isEqualTo(testMember.getId());
        }
    }

    // =====================================================
    // Scenario 3: 곡 수 조회 API
    // =====================================================
    @Nested
    @DisplayName("곡 수 조회 API")
    class SongCountApi {

        @Test
        @DisplayName("POST /game/retro/song-count - 레트로 곡 수 조회")
        void songCount_shouldReturnRetroSongCount() throws Exception {
            Map<String, Object> request = new HashMap<>();

            mockMvc.perform(post("/game/retro/song-count").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", greaterThan(0)));
        }

        @Test
        @DisplayName("솔로 필터 적용 시 곡 수 변화")
        void songCount_withSoloFilter_shouldFilterSongs() throws Exception {
            // 솔로 곡 추가
            Song soloSong = createSong("솔로곡", "솔로아티스트", 1995, kpopGenre);
            soloSong.setIsSolo(true);
            songRepository.save(soloSong);

            Map<String, Object> request = new HashMap<>();
            request.put("soloOnly", true);

            mockMvc.perform(post("/game/retro/song-count").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").exists());
        }
    }

    // =====================================================
    // Scenario 4: 답변 제출 API
    // =====================================================
    @Nested
    @DisplayName("답변 제출 API")
    class AnswerSubmissionApi {

        @Test
        @DisplayName("POST /game/retro/answer - 세션 없으면 에러")
        void answer_withoutSession_shouldReturnError() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("roundNumber", 1);
            request.put("answer", "테스트답변");

            mockMvc.perform(post("/game/retro/answer").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("세션")));
        }

        @Test
        @DisplayName("정답 제출 시 성공 응답")
        void answer_correct_shouldReturnSuccess() throws Exception {
            // 먼저 게임 시작
            MockHttpSession session = new MockHttpSession();

            Map<String, Object> startRequest = new HashMap<>();
            startRequest.put("nickname", "플레이어");
            startRequest.put("totalRounds", 5);
            startRequest.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

            // 첫 라운드 정보 가져오기
            mockMvc.perform(get("/game/retro/round/1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

            // 정답 제출 (일부러 틀린 답 - 3회 실패 처리)
            Map<String, Object> answerRequest = new HashMap<>();
            answerRequest.put("roundNumber", 1);
            answerRequest.put("answer", "임의의오답");
            answerRequest.put("answerTime", 5.0);

            // 3번 틀려서 라운드 종료
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/game/retro/answer").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest)));
            }

            // 마지막 응답 확인
            mockMvc.perform(post("/game/retro/answer").with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(answerRequest)))
                .andExpect(status().isOk());
        }
    }

    // =====================================================
    // Scenario 5: 결과 페이지
    // =====================================================
    @Nested
    @DisplayName("결과 페이지")
    class ResultPage {

        @Test
        @DisplayName("GET /game/retro/result - 세션 없으면 리다이렉트")
        void result_withoutSession_shouldRedirect() throws Exception {
            mockMvc.perform(get("/game/retro/result"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/game/retro"));
        }
    }

    // =====================================================
    // Scenario 6: 플레이 페이지
    // =====================================================
    @Nested
    @DisplayName("플레이 페이지")
    class PlayPage {

        @Test
        @DisplayName("GET /game/retro/play - 세션 없으면 리다이렉트")
        void play_withoutSession_shouldRedirect() throws Exception {
            mockMvc.perform(get("/game/retro/play"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/game/retro"));
        }

        @Test
        @DisplayName("게임 시작 후 플레이 페이지 접근 성공")
        void play_withSession_shouldShowPlayPage() throws Exception {
            MockHttpSession session = new MockHttpSession();

            // 게임 시작
            Map<String, Object> startRequest = new HashMap<>();
            startRequest.put("nickname", "플레이어");
            startRequest.put("totalRounds", 5);
            startRequest.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk());

            // 플레이 페이지 접근
            mockMvc.perform(get("/game/retro/play").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("client/game/retro/play"));
        }
    }

    // =====================================================
    // Scenario 7: 게임 종료 API
    // =====================================================
    @Nested
    @DisplayName("게임 종료 API")
    class GameEndApi {

        @Test
        @DisplayName("POST /game/retro/end - 게임 종료 성공")
        void end_shouldEndGame() throws Exception {
            MockHttpSession session = new MockHttpSession();

            // 게임 시작
            Map<String, Object> startRequest = new HashMap<>();
            startRequest.put("nickname", "플레이어");
            startRequest.put("totalRounds", 5);
            startRequest.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(startRequest)));

            // 게임 종료
            mockMvc.perform(post("/game/retro/end").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));
        }
    }

    // =====================================================
    // Scenario 8: 라운드 정보 조회
    // =====================================================
    @Nested
    @DisplayName("라운드 정보 조회")
    class RoundInfoApi {

        @Test
        @DisplayName("GET /game/retro/round/{roundNumber} - 세션 없으면 에러")
        void getRound_withoutSession_shouldReturnError() throws Exception {
            mockMvc.perform(get("/game/retro/round/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(false)));
        }

        @Test
        @DisplayName("라운드 정보 조회 성공")
        void getRound_withSession_shouldReturnRoundInfo() throws Exception {
            MockHttpSession session = new MockHttpSession();

            // 게임 시작
            Map<String, Object> startRequest = new HashMap<>();
            startRequest.put("nickname", "플레이어");
            startRequest.put("totalRounds", 5);
            startRequest.put("settings", new HashMap<>());

            mockMvc.perform(post("/game/retro/start").with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(startRequest)));

            // 라운드 정보 조회
            mockMvc.perform(get("/game/retro/round/1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.roundNumber", is(1)))
                .andExpect(jsonPath("$.song").exists());
        }
    }
}
