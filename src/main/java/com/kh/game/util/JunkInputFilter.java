package com.kh.game.util;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 정크 입력 필터링 유틸리티
 * - 테스트 데이터, 의미 없는 입력, 어뷰징 패턴 감지
 */
public class JunkInputFilter {

    // 최소 입력 길이 (한글 기준)
    private static final int MIN_INPUT_LENGTH = 2;

    // 키보드 스매싱 패턴 (연속된 키보드 배열)
    private static final Set<String> KEYBOARD_SMASH_PATTERNS = Set.of(
            "qwer", "asdf", "zxcv", "qwerty", "asdfgh", "zxcvbn",
            "ㅂㅈㄷㄱ", "ㅁㄴㅇㄹ", "ㅋㅌㅊㅍ", "ㄴㅇㄹㅎ",
            "1234", "12345", "123456", "1234567"
    );

    // 단일 문자 반복 패턴
    private static final Pattern REPEATED_CHAR_PATTERN = Pattern.compile("^(.)\\1{2,}$");

    // 특수문자/공백만 있는 패턴
    private static final Pattern SPECIAL_ONLY_PATTERN = Pattern.compile("^[\\s\\p{Punct}]+$");

    // 자음/모음만 있는 패턴 (한글)
    private static final Pattern JAMO_ONLY_PATTERN = Pattern.compile("^[ㄱ-ㅎㅏ-ㅣ]+$");

    // 의미 없는 일반적인 입력들
    private static final Set<String> MEANINGLESS_INPUTS = Set.of(
            "ㅇㅇ", "ㄴㄴ", "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅜㅜ",
            "test", "테스트", "aaa", "bbb", "111", "???",
            "모름", "몰라", "모르겠", "패스", "pass", "skip",
            "ㅁㄹ", "ㅁㄹㅁ"
    );

    /**
     * 입력값이 정크 데이터인지 판단
     *
     * @param input 사용자 입력
     * @return true면 정크 데이터 (필터링 대상)
     */
    public static boolean isJunkInput(String input) {
        if (input == null || input.isBlank()) {
            return true;
        }

        String normalized = input.trim().toLowerCase();

        // 1. 너무 짧은 입력
        if (normalized.length() < MIN_INPUT_LENGTH) {
            return true;
        }

        // 2. 의미 없는 일반적인 입력
        if (MEANINGLESS_INPUTS.contains(normalized)) {
            return true;
        }

        // 3. 특수문자/공백만 있는 경우
        if (SPECIAL_ONLY_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        // 4. 자음/모음만 있는 경우 (완성된 한글이 아님)
        if (JAMO_ONLY_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        // 5. 단일 문자 반복 (ㅋㅋㅋ, aaa 등)
        if (REPEATED_CHAR_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        // 6. 키보드 스매싱 패턴
        for (String pattern : KEYBOARD_SMASH_PATTERNS) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 입력값이 유효한 데이터인지 판단 (isJunkInput의 반대)
     */
    public static boolean isValidInput(String input) {
        return !isJunkInput(input);
    }

}
