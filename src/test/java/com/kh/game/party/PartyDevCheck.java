package com.kh.game.party;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 로컬 DB(dev 프로파일)로 파티 경로를 한 바퀴 돌려 보는 수동 점검. 자동 테스트가 아니다 —
 * 이름이 *Test 가 아니라 ./mvnw test 에 걸리지 않고, 로컬 DB 에 실제로 쓴다.
 *
 *   ./mvnw test -Dparty.devcheck=true -Dtest=PartyDevCheck#importAndPlay     (실제 TSV 적재 + 한 판)
 *   ./mvnw test -Dparty.devcheck=true -Dtest=PartyDevCheck#afterRestart      (새 JVM 에서 상태가 이어지는지)
 *
 * -Dparty.devcheck=true 없이는 돌지 않는다(IDE 의 "패키지 전체 실행"에 딸려 돌지 않게).
 * 진행 중인 게임(상태 파일)이 있으면 멈춘다 — 이 점검은 새 게임·초기화를 누른다.
 * 로그인은 하지 않는다(관리자 권한은 테스트 도구가 부여). 실제 로그인·브라우저 폴링은 화면이 생긴 뒤 확인한다.
 */
@EnabledIfSystemProperty(named = "party.devcheck", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
class PartyDevCheck {

    private static final Path CONTENT = Path.of("docs/party-content");
    private static final Path EXPECTED = Path.of("target/party-dev-check-expected.json");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PartyItemRepository partyItemRepository;
    @Value("${party.image-dir}")
    private String imageDir;
    @Value("${party.state-file}")
    private String stateFile;
    @Value("${party.speed-state-file}")
    private String speedStateFile;

    private static RequestPostProcessor admin() {
        return user("dev-check").roles("ADMIN");
    }

    private static final Path REPORT = Path.of("target/party-dev-check.log");

    /** 콘솔은 CP949 라 한글이 깨지므로 UTF-8 파일에도 남긴다. */
    private static void say(String line) {
        System.out.println("DEVCHECK| " + line);
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).with(admin())).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode act(String path, String... params) throws Exception {
        String statePath = path.startsWith("/admin/party/speed/") ? "/admin/party/speed/state" : "/admin/party/state";
        MockHttpServletRequestBuilder request = post(path).with(admin()).with(csrf())
                .param("version", getJson(statePath).get("version").asText());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        say(String.format("POST %-32s -> %d %s", path, result.getResponse().getStatus(),
                body.has("message") ? body.get("message").asText() : ""));
        return body;
    }

    private JsonNode importFile(Path file) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/admin/party/items/import")
                .file(new MockMultipartFile("file", file.getFileName().toString(), "text/tab-separated-values",
                        Files.readAllBytes(file)))
                .with(admin()).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Path> contentFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONTENT, "*.tsv")) {
            stream.forEach(files::add);
        }
        files.sort(null);
        return files;
    }

    @Test
    void importAndPlay(CapturedOutput output) throws Exception {
        assertThat(Path.of(stateFile)).as("진행 중인 게임이 있다 — 상태 파일을 먼저 옮겨 두고 돌린다").doesNotExist();
        assertThat(Path.of(speedStateFile)).as("진행 중인 스피드퀴즈가 있다").doesNotExist();
        Files.deleteIfExists(REPORT);

        // ---- 1. 실제 TSV 적재 ----
        int lines = 0;
        for (Path file : contentFiles()) {
            JsonNode result = importFile(file);
            lines += (int) Files.readString(file).lines().skip(1).filter(line -> !line.isBlank()).count();
            say(String.format("import %-11s created=%d updated=%d errors=%d", file.getFileName(),
                    result.get("created").asInt(), result.get("updated").asInt(), result.get("errors").size()));
            assertThat(result.get("errors")).isEmpty();
        }
        Map<String, Long> byCategory = new TreeMap<>();
        partyItemRepository.findAll().forEach(item -> byCategory.merge(item.getCategory().name(), 1L, Long::sum));
        say("rows in DB = " + partyItemRepository.count() + " (TSV data lines " + lines + ") " + byCategory);
        assertThat(partyItemRepository.count()).isEqualTo(lines);

        // ---- 2. 다시 올리기: MySQL 의 '같다' 규칙에서도 중복·예외 없이 전부 덮어쓰기 ----
        int updated = 0;
        for (Path file : contentFiles()) {
            JsonNode result = importFile(file);
            assertThat(result.get("created").asInt()).as(file + " created on re-import").isZero();
            updated += result.get("updated").asInt();
        }
        say("re-import: created=0 updated=" + updated + " rows in DB = " + partyItemRepository.count());
        assertThat(updated).isEqualTo(lines);
        assertThat(partyItemRepository.count()).isEqualTo(lines);

        // ---- 3. 이모지 왕복: DB 에서 읽은 문제텍스트가 TSV 와 글자 하나까지 같다 ----
        int emojiRows = 0;
        for (String line : Files.readString(CONTENT.resolve("quiz.tsv")).lines().skip(1).toList()) {
            String[] cells = line.split("\t", -1);
            if (cells.length != 19 || cells[10].codePoints().noneMatch(cp -> cp > 0xFFFF)) {
                continue;
            }
            emojiRows++;
            PartyItem stored = partyItemRepository.findByCategoryAndAnswer(PartyCategory.QUIZ, cells[3].trim())
                    .orElseThrow();
            assertThat(stored.getQuestionText()).isEqualTo(cells[10].trim());
        }
        say("emoji question texts identical after DB round trip: " + emojiRows + " rows");
        assertThat(emojiRows).isPositive();

        // ---- 4. 요약·남은 수 ----
        for (JsonNode row : getJson("/admin/party/items/summary")) {
            say(String.format("summary %-7s total=%d playable=%d incomplete=%d off=%d", row.get("category").asText(),
                    row.get("total").asInt(), row.get("playable").asInt(), row.get("incomplete").asInt(),
                    row.get("off").asInt()));
        }
        JsonNode remaining = getJson("/admin/party/remaining");
        say("remaining SONG " + remaining.get("SONG"));
        say("remaining QUIZ " + remaining.get("QUIZ"));
        assertThat(remaining.get("SONG").get("전체").asInt()).isPositive();

        // ---- 5. 본게임 한 판 ----
        act("/admin/party/new", "keepUsed", "false");
        JsonNode picked = act("/admin/party/pick", "category", "SONG", "subCategory", "2010~2014");
        say("song card: sub=" + picked.at("/state/card/subCategory").asText()
                + " hasAnswer=" + !picked.at("/state/card/answer").asText().isEmpty()
                + " detail=" + picked.at("/state/card/detail").asText());
        assertThat(picked.at("/state/card/subCategory").asText()).isEqualTo("2010~2014");
        act("/admin/party/show");
        assertThat(getJson("/admin/party/state").at("/item/videoId").asText()).isNotEmpty();
        act("/admin/party/play");
        act("/admin/party/correct", "team", "A");
        act("/admin/party/pick", "category", "QUIZ", "subCategory", "전체");
        act("/admin/party/show");
        act("/admin/party/miss");
        JsonNode noImage = act("/admin/party/pick", "category", "PERSON");
        assertThat(noImage.get("success").asBoolean()).as("사진 파일이 없으면 PERSON 은 뽑히지 않는다").isFalse();

        // ---- 6. 사진 파일을 넣으면 그 문제만 출제되고 사진이 열린다(자리 파일, 끝나면 지움) ----
        Path placeholder = Path.of(imageDir).resolve("person_01.jpg");
        boolean realImage = Files.exists(placeholder);
        if (!realImage) {
            Files.createDirectories(placeholder.getParent());
            Files.writeString(placeholder, "placeholder");
        }
        try {
            say("remaining PERSON with one file = " + getJson("/admin/party/remaining").get("PERSON").get("전체"));
            JsonNode person = act("/admin/party/pick", "category", "PERSON");
            assertThat(person.get("success").asBoolean()).isTrue();
            act("/admin/party/show");
            String imageUrl = getJson("/admin/party/state").at("/item/imageUrl").asText();
            int status = mockMvc.perform(get(imageUrl).with(admin())).andReturn().getResponse().getStatus();
            say("GET " + imageUrl + " -> " + status);
            assertThat(status).isEqualTo(200);
            act("/admin/party/correct", "team", "B");
        } finally {
            if (!realImage) {
                Files.deleteIfExists(placeholder);
            }
        }

        // ---- 7. 스피드퀴즈 한 턴 ----
        act("/admin/party/speed/reset");
        act("/admin/party/speed/setup", "topic", "동물", "limitA", "60", "limitB", "45", "mode", "TALK",
                "wordOnBoard", "true");
        act("/admin/party/speed/start", "team", "A");
        act("/admin/party/speed/correct");
        act("/admin/party/speed/pass");
        act("/admin/party/speed/undo");
        act("/admin/party/speed/correct");
        act("/admin/party/speed/finish");

        // ---- 8. 폴링 경로는 DB 를 읽지 않는다 (dev 는 SQL 을 DEBUG 로 찍는다) ----
        int before = output.getOut().length();
        for (int i = 0; i < 5; i++) {
            getJson("/admin/party/state");
            getJson("/admin/party/console/state");
            getJson("/admin/party/speed/state");
            getJson("/admin/party/speed/console/state");
        }
        String polled = output.getOut().substring(before);
        say("SQL statements during 20 polling requests: " + polled.split("Hibernate:|select ", -1).length / 2
                + " (raw 'select' occurrences " + (polled.split("select", -1).length - 1) + ")");
        assertThat(polled).doesNotContain("select");

        // ---- 9. 스냅샷 파일 + 재시작 뒤 비교할 값 ----
        say("state file " + Path.of(stateFile).toAbsolutePath().normalize() + " exists=" + Files.exists(Path.of(stateFile)));
        say("speed file " + Path.of(speedStateFile).toAbsolutePath().normalize() + " exists="
                + Files.exists(Path.of(speedStateFile)));
        assertThat(Path.of(stateFile)).exists();
        assertThat(Path.of(speedStateFile)).exists();
        Files.createDirectories(EXPECTED.getParent());
        objectMapper.writeValue(EXPECTED.toFile(), Map.of(
                "game", getJson("/admin/party/console/state"), "speed", getJson("/admin/party/speed/console/state")));
        say("before restart: " + summaryOf(getJson("/admin/party/console/state"), getJson("/admin/party/speed/state")));
    }

    @Test
    void afterRestart() throws Exception {
        JsonNode expected = objectMapper.readTree(EXPECTED.toFile());
        JsonNode game = getJson("/admin/party/console/state");
        JsonNode speed = getJson("/admin/party/speed/console/state");
        say("after restart:  " + summaryOf(game, speed.get("board")));

        assertThat(withoutClock(game)).isEqualTo(withoutClock(expected.get("game")));
        assertThat(speed.get("board").get("results")).isEqualTo(expected.get("speed").get("board").get("results"));
        assertThat(speed.get("setup")).isEqualTo(expected.get("speed").get("setup"));
        assertThat(speed.get("board").get("version").asLong())
                .as("재시작 뒤 버전은 앞보다 크다").isGreaterThan(expected.get("speed").get("board").get("version").asLong());
        assertThat(game.at("/board/version").asLong()).isGreaterThan(expected.get("game").at("/board/version").asLong());

        // 이어서 진행: 이미 낸 문제는 다시 나오지 않고 버전은 계속 오른다
        long version = game.at("/board/version").asLong();
        act("/admin/party/next");
        assertThat(getJson("/admin/party/state").get("version").asLong()).isGreaterThan(version);
        say("continued after restart: phase=" + getJson("/admin/party/state").get("phase").asText());
    }

    /** 버전과 경과 시간은 재시작·시간 흐름으로 달라지는 것이 정상이라 비교에서 뺀다. */
    private static JsonNode withoutClock(JsonNode gameConsole) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = gameConsole.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("board")).remove(List.of("version", "timerElapsedSeconds"));
        return copy;
    }

    private static String summaryOf(JsonNode gameConsole, JsonNode speedBoard) {
        return "phase=" + gameConsole.get("phase").asText() + " round=" + gameConsole.at("/board/round").asInt()
                + " scores=" + gameConsole.at("/board/scores") + " history=" + gameConsole.get("history").size()
                + " | speed phase=" + speedBoard.get("phase").asText() + " results=" + speedBoard.get("results");
    }
}
