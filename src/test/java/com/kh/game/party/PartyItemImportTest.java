package com.kh.game.party;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파티 문제 TSV 가져오기 (docs/party-content/*.tsv, 19열).
 *
 * 재생·표시할 것이 아직 없는 행(AUDIO 인데 URL 없음, IMAGE 인데 파일명 없음)은 저장한다 —
 * 수집이 끝나기 전에 목록부터 올리기 때문이다. 출제에서 빼는 것은 PartyGameService 몫.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("파티 문제 TSV 가져오기")
class PartyItemImportTest {

    private static final String HEADER = "대분류\t중분류\t제시\t정답\t인정답안\t보조\tYouTube URL\t시작초\t길이\t이미지파일명"
            + "\t문제텍스트\t힌트1\t힌트2\t힌트3\t출처\t제목스포\t난이도\t추천자\t비고";

    @Autowired
    private PartyItemImportService importService;

    @Autowired
    private PartyItemRepository partyItemRepository;

    /** 19열 한 줄. 뒤쪽 빈 열은 생략해도 채워 준다. */
    private static String row(String... cells) {
        String[] all = new String[19];
        for (int i = 0; i < 19; i++) {
            all[i] = i < cells.length ? cells[i] : "";
        }
        return String.join("\t", all);
    }

    private static String tsv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    @Test
    @DisplayName("[정상] 각 행이 party_item 한 줄로 저장된다")
    void savesEachRow() {
        PartyImportResult result = importService.importTsv(tsv(
                row("QUIZ", "초성 · 과자", "TEXT", "포카칩", "", "과자", "", "", "", "", "[과자] ㅍ ㅋ ㅊ",
                        "", "", "", "", "", "하"),
                row("GAME", "리그 오브 레전드", "AUDIO", "가렌", "가랜", "챔피언 대사", "", "", "", "", "",
                        "탑 · 전사", "데마시아 정예군 대장", "ㄱㄹ", "라이엇 게임즈 코리아", "", "상", "", "검색: 가렌 픽 대사")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(2);

        PartyItem garen = partyItemRepository.findByCategoryAndAnswer(PartyCategory.GAME, "가렌").orElseThrow();
        assertThat(garen.getSubCategory()).isEqualTo("리그 오브 레전드");
        assertThat(garen.getPresentation()).isEqualTo(PartyPresentation.AUDIO);
        assertThat(garen.getAnswerAliases()).isEqualTo("가랜");
        assertThat(garen.getDetail()).isEqualTo("챔피언 대사");
        assertThat(garen.getHint1()).isEqualTo("탑 · 전사");
        assertThat(garen.getHint3()).isEqualTo("ㄱㄹ");
        assertThat(garen.getSourceNote()).isEqualTo("라이엇 게임즈 코리아");
        assertThat(garen.getDifficulty()).isEqualTo(3);
        assertThat(garen.getYoutubeVideoId()).isNull();
        assertThat(garen.getUseYn()).isEqualTo("Y");

        PartyItem quiz = partyItemRepository.findByCategoryAndAnswer(PartyCategory.QUIZ, "포카칩").orElseThrow();
        assertThat(quiz.getQuestionText()).isEqualTo("[과자] ㅍ ㅋ ㅊ");
        assertThat(quiz.getDifficulty()).isEqualTo(1);
    }

    @Test
    @DisplayName("[정상] YouTube URL 에서 영상 ID 만 저장하고 시작초·길이를 숫자로 저장한다")
    void extractsVideoId() {
        PartyImportResult result = importService.importTsv(tsv(
                row("SOUND", "CM송", "AUDIO", "초코파이", "", "", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3s", "12", "6"),
                row("SOUND", "CM송", "AUDIO", "너구리", "", "", "https://youtu.be/abcdefghijk", "", "")));

        assertThat(result.errors()).isEmpty();
        PartyItem pie = partyItemRepository.findByCategoryAndAnswer(PartyCategory.SOUND, "초코파이").orElseThrow();
        assertThat(pie.getYoutubeVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(pie.getStartTime()).isEqualTo(12);
        assertThat(pie.getPlayDuration()).isEqualTo(6);
        assertThat(partyItemRepository.findByCategoryAndAnswer(PartyCategory.SOUND, "너구리").orElseThrow()
                .getYoutubeVideoId()).isEqualTo("abcdefghijk");
    }

    @Test
    @DisplayName("[정상] URL 없는 AUDIO, 파일명 없는 IMAGE 도 저장된다")
    void savesRowsWithoutMedia() {
        PartyImportResult result = importService.importTsv(tsv(
                row("SOUND", "TV 프로그램", "AUDIO", "무한도전", "무도"),
                row("ANIME", "국내", "IMAGE", "로보카 폴리", "폴리")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(2);
    }

    @Test
    @DisplayName("[정상] SPEED 제시어는 문제텍스트 없이 저장된다")
    void speedNeedsNoQuestionText() {
        PartyImportResult result = importService.importTsv(tsv(
                row("SPEED", "동물", "TEXT", "코끼리")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("[예외] 필수값이 빠진 행만 거부되고 행 번호와 이유가 보고된다")
    void reportsMissingRequired() {
        PartyImportResult result = importService.importTsv(tsv(
                row("QUIZ", "초성 · 과자", "TEXT", "홈런볼", "", "", "", "", "", "", "[과자] ㅎ ㄹ ㅂ"),
                row("QUIZ", "초성 · 과자", "TEXT", "", "", "", "", "", "", "", "[과자] ㅊ ㅋ ㅍ ㅇ"),
                row("QUIZ", "초성 · 과자", "TEXT", "꼬깔콘")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).extracting(PartyImportResult.RowError::line).containsExactly(3, 4);
        assertThat(result.errors()).allSatisfy(e -> assertThat(e.reason()).isNotBlank());
        assertThat(partyItemRepository.findByCategoryAndAnswer(PartyCategory.QUIZ, "꼬깔콘")).isEmpty();
    }

    @Test
    @DisplayName("[예외] 모르는 대분류·제시·난이도, 열 수가 다른 행, 읽을 수 없는 URL·숫자는 거부된다")
    void rejectsMalformedRows() {
        PartyImportResult result = importService.importTsv(tsv(
                row("MOVIE", "", "IMAGE", "기생충"),
                row("SCREEN", "", "VIDEO", "기생충"),
                row("SCREEN", "", "IMAGE", "기생충", "", "", "", "", "", "", "", "", "", "", "", "", "최상"),
                "SCREEN\t\tIMAGE\t기생충",
                row("SOUND", "CM송", "AUDIO", "새우깡", "", "", "https://example.com/video"),
                row("SOUND", "CM송", "AUDIO", "짜파게티", "", "", "", "십이초", "")));

        assertThat(result.created()).isZero();
        assertThat(result.errors()).extracting(PartyImportResult.RowError::line)
                .containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(partyItemRepository.count()).isZero();
    }

    @Test
    @DisplayName("[경계] 열 크기를 넘는 값은 그 행만 거부되고 나머지는 저장된다")
    void rejectsTooLongValue() {
        PartyImportResult result = importService.importTsv(tsv(
                row("SPEED", "동물", "TEXT", "가".repeat(256)),
                row("SPEED", "나".repeat(51), "TEXT", "사자"),
                row("SPEED", "동물", "TEXT", "가".repeat(255))));

        assertThat(result.errors()).extracting(PartyImportResult.RowError::line).containsExactly(2, 3);
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("[경계] 같은 파일을 다시 올리면 대분류+정답이 같은 행을 덮어쓴다")
    void reimportOverwrites() {
        importService.importTsv(tsv(row("SOUND", "CM송", "AUDIO", "초코파이", "", "오리온")));
        Long id = partyItemRepository.findByCategoryAndAnswer(PartyCategory.SOUND, "초코파이").orElseThrow().getId();

        PartyImportResult second = importService.importTsv(tsv(
                row("SOUND", "CM송", "AUDIO", "초코파이", "정", "오리온 1974", "https://youtu.be/abcdefghijk", "3", "5")));

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        List<PartyItem> all = partyItemRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(id);
        assertThat(all.get(0).getAnswerAliases()).isEqualTo("정");
        assertThat(all.get(0).getDetail()).isEqualTo("오리온 1974");
        assertThat(all.get(0).getYoutubeVideoId()).isEqualTo("abcdefghijk");
    }

    @Test
    @DisplayName("[경계] 정답이 같아도 대분류가 다르면 다른 문제다")
    void sameAnswerDifferentCategory() {
        PartyImportResult result = importService.importTsv(tsv(
                row("SPEED", "음식", "TEXT", "라면"),
                row("SOUND", "CM송", "AUDIO", "라면")));

        assertThat(result.created()).isEqualTo(2);
    }

    @Test
    @DisplayName("[경계] 머리글만 있거나 빈 줄·CRLF 가 섞여도 읽는다")
    void toleratesBlankLinesAndCrlf() {
        assertThat(importService.importTsv(HEADER + "\n").created()).isZero();

        PartyImportResult result = importService.importTsv(
                HEADER + "\r\n" + row("SPEED", "동물", "TEXT", "기린") + "\r\n\r\n");

        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("[정상] docs/party-content 의 실제 TSV 가 거부되는 행 없이 전부 저장된다")
    void importsRealContentFiles() throws IOException {
        int dataLines = 0;
        int created = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("docs/party-content"), "*.tsv")) {
            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                dataLines += (int) content.lines().skip(1).filter(line -> !line.isBlank()).count();

                PartyImportResult result = importService.importTsv(content);

                assertThat(result.errors()).as(file.getFileName().toString()).isEmpty();
                assertThat(result.updated()).as(file.getFileName() + " 안에 대분류+정답 중복").isZero();
                created += result.created();
            }
        }
        assertThat(dataLines).isPositive();
        assertThat(created).isEqualTo(dataLines);
        assertThat(partyItemRepository.count()).isEqualTo(dataLines);
    }

    @Test
    @DisplayName("[예외] 머리글이 19열 형식이 아니면 아무것도 저장하지 않는다")
    void rejectsWrongHeader() {
        PartyImportResult result = importService.importTsv("category,answer\nQUIZ,포카칩\n");

        assertThat(result.created()).isZero();
        assertThat(result.errors()).extracting(PartyImportResult.RowError::line).containsExactly(1);
        assertThat(partyItemRepository.count()).isZero();
    }
}
