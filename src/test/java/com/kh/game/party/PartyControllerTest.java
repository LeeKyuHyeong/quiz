package com.kh.game.party;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/party/** 경로: 권한 · CSRF · 조작과 응답 · 오류 코드 · TSV 올리기.
 * 게임 상태는 서버 전체에 1개라 테스트마다 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("파티 경로 (/admin/party/**)")
class PartyControllerTest {

    private static final String HEADER = "대분류\t중분류\t제시\t정답\t인정답안\t보조\tYouTube URL\t시작초\t길이\t이미지파일명"
            + "\t문제텍스트\t힌트1\t힌트2\t힌트3\t출처\t제목스포\t난이도\t추천자\t비고";
    private static final List<String> GET_PATHS = List.of("/admin/party/state", "/admin/party/console/state",
            "/admin/party/remaining", "/admin/party/speed/state", "/admin/party/speed/console/state",
            "/admin/party/speed/topics", "/admin/party/items/summary");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PartyGameService gameService;
    @Autowired
    private PartySpeedQuizService speedService;
    @Autowired
    private PartyItemRepository partyItemRepository;

    @BeforeEach
    void setUp() {
        gameService.newGame();
        speedService.reset();
    }

    private static RequestPostProcessor admin() {
        return user("admin@test.com").roles("ADMIN");
    }

    private static RequestPostProcessor member() {
        return user("user@test.com").roles("USER");
    }

    private static String row(String... cells) {
        String[] all = new String[19];
        for (int i = 0; i < 19; i++) {
            all[i] = i < cells.length ? cells[i] : "";
        }
        return String.join("\t", all);
    }

    private void upload(String... rows) throws Exception {
        String tsv = HEADER + "\n" + String.join("\n", rows) + "\n";
        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "quiz.tsv", "text/tab-separated-values",
                                tsv.getBytes(StandardCharsets.UTF_8)))
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());
    }

    private long version() throws Exception {
        return json(mockMvc.perform(get("/admin/party/state").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("version").asLong();
    }

    private long speedVersion() throws Exception {
        return json(mockMvc.perform(get("/admin/party/speed/state").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("version").asLong();
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /** 관리자 + CSRF + 지금 버전으로 본게임 조작. */
    private MockHttpServletRequestBuilder act(String path) throws Exception {
        return post("/admin/party/" + path).param("version", String.valueOf(version()))
                .with(admin()).with(csrf());
    }

    private MockHttpServletRequestBuilder speedAct(String path) throws Exception {
        return post("/admin/party/speed/" + path).param("version", String.valueOf(speedVersion()))
                .with(admin()).with(csrf());
    }

    // ---------- 권한 ----------

    @Test
    @DisplayName("[권한] 로그인하지 않으면 조회·조작 모두 로그인 화면으로 보내진다")
    void anonymousIsRedirected() throws Exception {
        for (String path : GET_PATHS) {
            mockMvc.perform(get(path)).andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/admin/party/score").param("version", "0").param("team", "A").param("delta", "1")
                .with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(gameService.boardView().scores()).containsEntry("A", 0);
    }

    @Test
    @DisplayName("[권한] 일반 회원은 조회·조작 모두 403 이다")
    void memberIsForbidden() throws Exception {
        for (String path : GET_PATHS) {
            mockMvc.perform(get(path).with(member())).andExpect(status().isForbidden());
        }
        long version = gameService.boardView().version();
        mockMvc.perform(post("/admin/party/score").param("version", String.valueOf(version))
                .param("team", "A").param("delta", "1").with(member()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/party/speed/reset").param("version", "0").with(member()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain", HEADER.getBytes(StandardCharsets.UTF_8)))
                        .with(member()).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(gameService.boardView().scores()).containsEntry("A", 0);
    }

    @Test
    @DisplayName("[권한] 관리자라도 CSRF 토큰 없는 POST 는 403 이고 상태가 바뀌지 않는다")
    void postNeedsCsrf() throws Exception {
        long version = version();

        mockMvc.perform(post("/admin/party/score").param("version", String.valueOf(version))
                .param("team", "A").param("delta", "1").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/party/speed/reset").param("version", "0").with(admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain",
                                (HEADER + "\n" + row("SPEED", "동물", "TEXT", "기린")).getBytes(StandardCharsets.UTF_8)))
                        .with(admin()))
                .andExpect(status().isForbidden());

        assertThat(gameService.boardView().scores()).containsEntry("A", 0);
        assertThat(version()).isEqualTo(version);
        assertThat(partyItemRepository.count()).isZero();
    }

    // ---------- 본게임 ----------

    @Test
    @DisplayName("[정상] 올리기 → 뽑기 → 띄우기 → 정답. 보드 상태에는 정답 공개 전까지 정답이 없다")
    void playsOneRound() throws Exception {
        upload(row("QUIZ", "초성 · 과자", "TEXT", "포카칩", "", "과자", "", "", "", "", "[과자] ㅍ ㅋ ㅊ"));

        mockMvc.perform(get("/admin/party/remaining").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.QUIZ['초성 · 과자']").value(1))
                .andExpect(jsonPath("$.SONG['전체']").value(0));

        mockMvc.perform(act("pick").param("category", "QUIZ").param("subCategory", "초성 · 과자"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.state.phase").value("READY"))
                .andExpect(jsonPath("$.state.card.answer").value("포카칩"))
                .andExpect(jsonPath("$.remaining.QUIZ['전체']").value(0));

        mockMvc.perform(get("/admin/party/state").with(admin()))
                .andExpect(jsonPath("$.phase").value("WAIT"))
                .andExpect(content().string(not(containsString("포카칩"))));

        mockMvc.perform(act("show")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.round").value(1));
        mockMvc.perform(get("/admin/party/state").with(admin()))
                .andExpect(jsonPath("$.item.questionText").value("[과자] ㅍ ㅋ ㅊ"))
                .andExpect(content().string(not(containsString("포카칩"))));

        mockMvc.perform(act("wrong").param("team", "A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.wrongTeam").value("A"));
        mockMvc.perform(act("correct").param("team", "B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.scores.B").value(1))
                .andExpect(jsonPath("$.state.board.reveal.answer").value("포카칩"));

        mockMvc.perform(get("/admin/party/console/state").with(admin()))
                .andExpect(jsonPath("$.phase").value("REVEAL"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].scoringTeam").value("B"));

        mockMvc.perform(act("next")).andExpect(status().isOk());
        mockMvc.perform(act("scores")).andExpect(jsonPath("$.state.board.phase").value("SCORES"));
        mockMvc.perform(act("wait")).andExpect(jsonPath("$.state.board.phase").value("WAIT"));
        mockMvc.perform(act("score").param("team", "A").param("delta", "1"))
                .andExpect(jsonPath("$.state.board.scores.A").value(1));
        mockMvc.perform(act("end")).andExpect(jsonPath("$.state.board.phase").value("END"));
        mockMvc.perform(act("new").param("keepUsed", "false"))
                .andExpect(jsonPath("$.state.board.scores.B").value(0));
    }

    @Test
    @DisplayName("[정상] GAME 문제는 재생 명령과 힌트 열기가 된다")
    void playsAudioWithHints() throws Exception {
        upload(row("GAME", "리그 오브 레전드", "AUDIO", "가렌", "", "", "https://youtu.be/abcdefghijk", "3", "5",
                "", "", "탑 · 전사", "데마시아", "ㄱㄹ"));

        mockMvc.perform(act("pick").param("category", "GAME")).andExpect(status().isOk());
        mockMvc.perform(act("show")).andExpect(status().isOk());
        mockMvc.perform(act("play")).andExpect(jsonPath("$.state.board.player.cmd").value("PLAY"));
        mockMvc.perform(act("pause")).andExpect(jsonPath("$.state.board.player.cmd").value("PAUSE"));
        mockMvc.perform(act("restart")).andExpect(jsonPath("$.state.board.player.cmd").value("RESTART"));
        mockMvc.perform(act("hint")).andExpect(jsonPath("$.state.board.hints", hasSize(1)));
        mockMvc.perform(act("miss")).andExpect(jsonPath("$.state.board.reveal.scoringTeam").doesNotExist());
    }

    @Test
    @DisplayName("[예외] 지금 단계에서 할 수 없는 조작은 409 와 이유를 돌려주고 상태는 그대로다")
    void wrongPhaseIs409() throws Exception {
        long before = version();

        mockMvc.perform(act("correct").param("team", "A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("문제가 떠 있지 않습니다"))
                .andExpect(jsonPath("$.stale").doesNotExist());
        mockMvc.perform(act("pick").param("category", "QUIZ"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("남은 문제가 없습니다"));

        assertThat(version()).isEqualTo(before);
    }

    @Test
    @DisplayName("[R-037] 같은 버전으로 두 번 보낸 조작은 두 번째가 409(stale) 이고 한 번만 적용된다")
    void staleVersionIs409() throws Exception {
        String version = String.valueOf(version());

        mockMvc.perform(post("/admin/party/score").param("version", version).param("team", "A").param("delta", "1")
                .with(admin()).with(csrf())).andExpect(status().isOk());
        mockMvc.perform(post("/admin/party/score").param("version", version).param("team", "A").param("delta", "1")
                        .with(admin()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.stale").value(true));

        assertThat(gameService.boardView().scores()).containsEntry("A", 1);
    }

    @Test
    @DisplayName("[예외] 모르는 대분류·팀·묶음, 빠진 값은 400 이다")
    void badInputIs400() throws Exception {
        mockMvc.perform(act("pick").param("category", "MOVIE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(act("pick").param("category", "SPEED")).andExpect(status().isBadRequest());
        mockMvc.perform(act("pick").param("category", "SONG").param("subCategory", "없는 묶음"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(act("pick")).andExpect(status().isBadRequest());
        mockMvc.perform(act("correct").param("team", "C")).andExpect(status().isBadRequest());
        mockMvc.perform(act("score").param("team", "A").param("delta", "많이")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/party/show").with(admin()).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("version")));
    }

    @Test
    @DisplayName("[R-047] 띄운 문제 거두기(/cancel): 정답 공개 없이 대기로, 이력 없음")
    void cancelsShownQuestion() throws Exception {
        upload(row("QUIZ", "초성 · 과자", "TEXT", "포카칩", "", "", "", "", "", "", "[과자] ㅍ ㅋ ㅊ"));
        mockMvc.perform(act("pick").param("category", "QUIZ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.item.questionText").value("[과자] ㅍ ㅋ ㅊ"))
                .andExpect(jsonPath("$.state.board.item").doesNotExist());
        mockMvc.perform(act("show")).andExpect(status().isOk());

        mockMvc.perform(act("cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.phase").value("WAIT"))
                .andExpect(jsonPath("$.state.board.round").value(0))
                .andExpect(jsonPath("$.state.history", hasSize(0)));
        mockMvc.perform(get("/admin/party/state").with(admin()))
                .andExpect(content().string(not(containsString("포카칩"))));
        mockMvc.perform(act("cancel")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[R-048] 새 게임은 '낸 문제를 남길지'를 반드시 받는다 — 빠지면 400 이고 게임은 그대로다")
    void newGameNeedsKeepUsed() throws Exception {
        upload(row("QUIZ", "초성 · 과자", "TEXT", "포카칩", "", "", "", "", "", "", "[과자] ㅍ ㅋ ㅊ"));
        mockMvc.perform(act("pick").param("category", "QUIZ")).andExpect(status().isOk());
        mockMvc.perform(act("show")).andExpect(status().isOk());
        mockMvc.perform(act("correct").param("team", "A")).andExpect(status().isOk());

        mockMvc.perform(act("new")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("keepUsed")));
        assertThat(gameService.boardView().scores()).containsEntry("A", 1);

        mockMvc.perform(act("new").param("keepUsed", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.scores.A").value(0))
                .andExpect(jsonPath("$.remaining.QUIZ['전체']").value(0));
    }

    @Test
    @DisplayName("[R-049] 스피드퀴즈 설정은 주제를 여러 개 받는다")
    void speedSetupTakesSeveralTopics() throws Exception {
        upload(row("SPEED", "동물", "TEXT", "코끼리"), row("SPEED", "직업", "TEXT", "소방관"),
                row("SPEED", "물건", "TEXT", "우산"));

        mockMvc.perform(speedAct("setup").param("topic", "동물", "직업").param("limitA", "60").param("limitB", "45")
                        .param("mode", "TALK").param("wordOnBoard", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.setup.topics", hasSize(2)))
                .andExpect(jsonPath("$.state.board.limits.B").value(45));
        mockMvc.perform(speedAct("setup").param("limitA", "60").param("limitB", "45")
                        .param("mode", "TALK").param("wordOnBoard", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.setup.topics", hasSize(0)));
    }

    // ---------- 스피드퀴즈 ----------

    @Test
    @DisplayName("[정상] 스피드퀴즈: 설정 → 시작 → 정답·패스·되돌리기 → 턴 종료 → 재대결 → 초기화")
    void playsSpeedQuiz() throws Exception {
        upload(row("SPEED", "동물", "TEXT", "코끼리"), row("SPEED", "동물", "TEXT", "기린"),
                row("SPEED", "동물", "TEXT", "사자"), row("SPEED", "직업", "TEXT", "소방관"));

        mockMvc.perform(get("/admin/party/speed/topics").with(admin()))
                .andExpect(jsonPath("$['동물']").value(3)).andExpect(jsonPath("$['전체']").value(4));

        mockMvc.perform(speedAct("setup").param("topic", "동물").param("limitA", "60").param("limitB", "45")
                        .param("mode", "BODY").param("wordOnBoard", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.setup.limitSeconds.B").value(45));
        mockMvc.perform(speedAct("start").param("team", "A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.board.phase").value("RUN"))
                .andExpect(jsonPath("$.state.word").isNotEmpty())
                .andExpect(jsonPath("$.state.board.word").doesNotExist())
                .andExpect(jsonPath("$.topics['동물']").value(2));
        mockMvc.perform(speedAct("correct")).andExpect(jsonPath("$.state.board.results.A.correct").value(1));
        mockMvc.perform(speedAct("pass")).andExpect(jsonPath("$.state.board.results.A.pass").value(1));
        mockMvc.perform(speedAct("undo")).andExpect(jsonPath("$.state.board.results.A.pass").value(0));
        mockMvc.perform(speedAct("finish")).andExpect(jsonPath("$.state.board.results.A.done").value(true));
        mockMvc.perform(get("/admin/party/speed/console/state").with(admin()))
                .andExpect(jsonPath("$.board.phase").value("OVER"));
        mockMvc.perform(speedAct("rematch")).andExpect(jsonPath("$.state.board.phase").value("WAIT"));
        mockMvc.perform(speedAct("reset")).andExpect(jsonPath("$.state.setup.limitSeconds.B").value(90));
    }

    @Test
    @DisplayName("[예외] 스피드퀴즈: 두 번 누른 정답은 409(stale), 단계 오류는 409, 틀린 값은 400")
    void speedErrors() throws Exception {
        upload(row("SPEED", "동물", "TEXT", "코끼리"), row("SPEED", "동물", "TEXT", "기린"),
                row("SPEED", "동물", "TEXT", "사자"));

        mockMvc.perform(speedAct("correct")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.stale").doesNotExist());
        mockMvc.perform(speedAct("setup").param("limitA", "0").param("limitB", "90").param("mode", "TALK")
                        .param("wordOnBoard", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("제한 시간")));
        mockMvc.perform(speedAct("setup").param("limitA", "90").param("limitB", "90").param("mode", "SING")
                        .param("wordOnBoard", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("mode")));
        // 표시 위치를 빼먹은 설정 요청이 제시어를 조용히 TV 로 되돌리지 않게, 빠지면 거부한다
        mockMvc.perform(speedAct("setup").param("limitA", "90").param("limitB", "90").param("mode", "TALK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("wordOnBoard")));
        assertThat(speedService.consoleView().setup().isWordOnBoard()).isTrue();
        mockMvc.perform(speedAct("start").param("team", "C")).andExpect(status().isBadRequest());

        mockMvc.perform(speedAct("start").param("team", "A")).andExpect(status().isOk());
        String version = String.valueOf(speedVersion());
        mockMvc.perform(post("/admin/party/speed/correct").param("version", version).with(admin()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/party/speed/correct").param("version", version).with(admin()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.stale").value(true));

        assertThat(speedService.boardView().results().get("A").correct()).isEqualTo(1);
    }

    // ---------- 문제 올리기 ----------

    @Test
    @DisplayName("[정상] TSV 를 올리면 만든 수·덮어쓴 수·거부된 행을 돌려준다")
    void importReportsResult() throws Exception {
        String tsv = HEADER + "\n" + row("SPEED", "동물", "TEXT", "코끼리") + "\n"
                + row("MOVIE", "", "IMAGE", "기생충") + "\n";

        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain", tsv.getBytes(StandardCharsets.UTF_8)))
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].reason", containsString("대분류")));
    }

    @Test
    @DisplayName("[예외] 빈 파일, UTF-8 이 아닌 파일, file 이 빠진 요청은 400 이고 아무것도 저장되지 않는다")
    void importRejectsBadFile() throws Exception {
        byte[] cp949 = (HEADER + "\n" + row("SPEED", "동물", "TEXT", "코끼리")).getBytes(Charset.forName("MS949"));
        byte[] utf16 = (HEADER + "\n" + row("SPEED", "동물", "TEXT", "코끼리")).getBytes(StandardCharsets.UTF_16LE);

        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain", new byte[0]))
                        .with(admin()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain", cp949))
                        .with(admin()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("UTF-8")));
        mockMvc.perform(multipart("/admin/party/items/import")
                        .file(new MockMultipartFile("file", "a.tsv", "text/plain", utf16))
                        .with(admin()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/admin/party/items/import").with(admin()).with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(partyItemRepository.count()).isZero();
    }

    @Test
    @DisplayName("[정상] 요약은 대분류별 전체·꺼짐·낼 수 있음·미완성 수를 보여 준다 (사진 파일이 없으면 미완성)")
    void summaryCounts() throws Exception {
        upload(row("QUIZ", "초성 · 과자", "TEXT", "포카칩", "", "", "", "", "", "", "[과자] ㅍ ㅋ ㅊ"),
                row("SOUND", "CM송", "AUDIO", "초코파이"),
                row("SOUND", "CM송", "AUDIO", "너구리", "", "", "https://youtu.be/abcdefghijk"),
                row("PERSON", "공통", "IMAGE", "유재석", "", "", "", "", "", "person_01.jpg"),
                row("SPEED", "동물", "TEXT", "코끼리"));

        mockMvc.perform(get("/admin/party/items/summary").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category=='QUIZ')].playable").value(1))
                .andExpect(jsonPath("$[?(@.category=='SOUND')].total").value(2))
                .andExpect(jsonPath("$[?(@.category=='SOUND')].playable").value(1))
                .andExpect(jsonPath("$[?(@.category=='SOUND')].incomplete").value(1))
                .andExpect(jsonPath("$[?(@.category=='PERSON')].playable").value(0))
                .andExpect(jsonPath("$[?(@.category=='PERSON')].incomplete").value(1))
                .andExpect(jsonPath("$[?(@.category=='SPEED')].playable").value(1))
                .andExpect(jsonPath("$[?(@.category=='ANIME')].total").value(0));
    }
}
