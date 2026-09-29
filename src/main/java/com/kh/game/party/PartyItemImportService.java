package com.kh.game.party;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파티 문제 TSV(docs/party-content, 19열) 가져오기.
 * 잘못된 행은 건너뛰고 줄 번호와 이유를 돌려준다. 대분류 + 정답이 같은 행은 덮어쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyItemImportService {

    private static final int COLUMN_COUNT = 19;
    private static final String FIRST_HEADER = "대분류";
    private static final Map<String, Integer> DIFFICULTY = Map.of("하", 1, "중", 2, "상", 3);
    private static final Pattern VIDEO_ID = Pattern.compile("^[a-zA-Z0-9_-]{11}$");
    private static final Pattern VIDEO_URL = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/)|youtu\\.be/)([a-zA-Z0-9_-]{11})");

    // 열 위치 (제목스포 15 · 추천자 17 · 비고 18 은 수집용 메모라 저장하지 않는다)
    private static final int CATEGORY = 0, SUB_CATEGORY = 1, PRESENTATION = 2, ANSWER = 3, ALIASES = 4, DETAIL = 5,
            YOUTUBE_URL = 6, START_TIME = 7, PLAY_DURATION = 8, IMAGE = 9, QUESTION_TEXT = 10,
            HINT1 = 11, HINT2 = 12, HINT3 = 13, SOURCE = 14, DIFFICULTY_COL = 16;

    private final PartyItemRepository partyItemRepository;

    @Transactional
    public PartyImportResult importTsv(String content) {
        String[] lines = content.replace("﻿", "").split("\r?\n", -1);
        List<PartyImportResult.RowError> errors = new ArrayList<>();

        String[] header = lines[0].split("\t", -1);
        if (header.length != COLUMN_COUNT || !FIRST_HEADER.equals(header[0].trim())) {
            errors.add(new PartyImportResult.RowError(1, "머리글이 19열 형식이 아닙니다"));
            return new PartyImportResult(0, 0, errors);
        }

        int created = 0;
        int updated = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            try {
                if (apply(lines[i].split("\t", -1))) {
                    created++;
                } else {
                    updated++;
                }
            } catch (InvalidRowException e) {
                errors.add(new PartyImportResult.RowError(lineNo, e.getMessage()));
            }
        }
        log.info("Party item import: created={} updated={} rejected={}", created, updated, errors.size());
        return new PartyImportResult(created, updated, errors);
    }

    /** @return 새로 만들었으면 true, 기존 행을 덮어썼으면 false */
    private boolean apply(String[] cells) {
        if (cells.length != COLUMN_COUNT) {
            throw new InvalidRowException("열 수가 " + cells.length + "개입니다(19개여야 함)");
        }
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }

        PartyCategory category = parseEnum(PartyCategory.class, cells[CATEGORY], "대분류");
        PartyPresentation presentation = parseEnum(PartyPresentation.class, cells[PRESENTATION], "제시");
        String answer = cells[ANSWER];
        limited(answer, 255, "정답");
        String questionText = limited(cells[QUESTION_TEXT], 500, "문제텍스트");

        String missing = missingRequired(category, presentation, answer, questionText);
        if (missing != null) {
            throw new InvalidRowException(missing);
        }

        String videoId = parseVideoId(cells[YOUTUBE_URL]);
        Integer startTime = parseSeconds(cells[START_TIME], "시작초");
        Integer playDuration = parseSeconds(cells[PLAY_DURATION], "길이");
        Integer difficulty = parseDifficulty(cells[DIFFICULTY_COL]);

        PartyItem item = partyItemRepository.findByCategoryAndAnswer(category, answer).orElse(null);
        boolean isNew = item == null;
        if (isNew) {
            item = new PartyItem();
            item.setCategory(category);
            item.setAnswer(answer);
        }
        item.setSubCategory(limited(cells[SUB_CATEGORY], 50, "중분류"));
        item.setPresentation(presentation);
        item.setAnswerAliases(limited(cells[ALIASES], 500, "인정답안"));
        item.setDetail(limited(cells[DETAIL], 255, "보조"));
        item.setYoutubeVideoId(videoId);
        item.setStartTime(startTime);
        item.setPlayDuration(playDuration);
        item.setImagePath(limited(cells[IMAGE], 255, "이미지파일명"));
        item.setQuestionText(questionText);
        item.setHint1(limited(cells[HINT1], 255, "힌트1"));
        item.setHint2(limited(cells[HINT2], 255, "힌트2"));
        item.setHint3(limited(cells[HINT3], 255, "힌트3"));
        item.setSourceNote(limited(cells[SOURCE], 255, "출처"));
        item.setDifficulty(difficulty);
        partyItemRepository.save(item);
        return isNew;
    }

    /**
     * 이 행을 저장할 수 없게 만드는 빠진 값이 있으면 그 이유, 없으면 null.
     * AUDIO 의 URL, IMAGE 의 파일명은 여기서 요구하지 않는다(수집 전에 목록부터 올린다).
     */
    private String missingRequired(PartyCategory category, PartyPresentation presentation,
                                   String answer, String questionText) {
        if (answer.isEmpty()) {
            return "정답이 비어 있습니다";
        }
        // SPEED 는 정답이 곧 제시어라 문제텍스트가 없다
        if (presentation == PartyPresentation.TEXT && category != PartyCategory.SPEED && questionText == null) {
            return "제시가 TEXT 인데 문제텍스트가 비어 있습니다";
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String column) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRowException(column + " 값을 알 수 없습니다: '" + value + "'");
        }
    }

    private static String parseVideoId(String url) {
        if (url.isEmpty()) {
            return null;
        }
        if (VIDEO_ID.matcher(url).matches()) {
            return url;
        }
        Matcher matcher = VIDEO_URL.matcher(url);
        if (!matcher.find()) {
            throw new InvalidRowException("YouTube URL 을 읽을 수 없습니다: '" + url + "'");
        }
        return matcher.group(1);
    }

    private static Integer parseSeconds(String value, String column) {
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("\\d{1,5}")) {
            throw new InvalidRowException(column + " 은 0 이상의 정수(초)여야 합니다: '" + value + "'");
        }
        return Integer.valueOf(value);
    }

    private static Integer parseDifficulty(String value) {
        if (value.isEmpty()) {
            return null;
        }
        Integer difficulty = DIFFICULTY.get(value);
        if (difficulty == null) {
            throw new InvalidRowException("난이도는 하/중/상 이어야 합니다: '" + value + "'");
        }
        return difficulty;
    }

    /** 빈 값은 null 로, 열 크기를 넘으면 거부. */
    private static String limited(String value, int maxLength, String column) {
        if (value.length() > maxLength) {
            throw new InvalidRowException(column + " 이 " + maxLength + "자를 넘습니다(" + value.length() + "자)");
        }
        return value.isEmpty() ? null : value;
    }

    private static class InvalidRowException extends RuntimeException {
        InvalidRowException(String message) {
            super(message);
        }
    }
}
