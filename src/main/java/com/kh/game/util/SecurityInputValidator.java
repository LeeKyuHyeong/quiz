package com.kh.game.util;

import java.util.regex.Pattern;

/**
 * 보안 입력 검증 유틸리티
 * - 이메일 형식 검증
 * - SQL Injection / 자동화 공격 페이로드 탐지
 *
 * JPA가 SQL Injection을 차단하더라도, 이 유틸은 다음 목적으로 사용:
 * 1. 명백한 공격 페이로드의 DB 도달 자체를 차단 (로그 오염 방지)
 * 2. 비정상 입력에 빠른 400 응답 (자원 절약)
 * 3. WAF 미사용 환경의 1차 방어선
 */
public final class SecurityInputValidator {

    private SecurityInputValidator() {}

    // RFC 5322 단순화 정규식 (AuthController.register와 동일)
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    // 이메일 최대 길이 (RFC 5321: local 64 + @ + domain 255 = 320)
    private static final int MAX_EMAIL_LENGTH = 320;

    // SQL Injection / time-based blind 공격 시그니처
    // 일반 사용자 입력엔 절대 등장하지 않는 패턴들
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "(?i)(" +
                    // SQL 주석
                    "--|/\\*|\\*/|#" +
                    // 시간 지연 함수
                    "|\\bSLEEP\\s*\\(" +
                    "|\\bPG_SLEEP\\s*\\(" +
                    "|\\bBENCHMARK\\s*\\(" +
                    "|\\bWAITFOR\\s+DELAY\\b" +
                    "|\\bDBMS_PIPE\\." +
                    // 부울 기반 블라인드
                    "|\\bOR\\s+\\d+\\s*=\\s*\\d+" +
                    "|\\bAND\\s+\\d+\\s*=\\s*\\d+" +
                    "|\\bXOR\\s*\\(" +
                    // UNION 기반
                    "|\\bUNION\\s+(ALL\\s+)?SELECT\\b" +
                    // 시스템 함수
                    "|\\bSYSDATE\\s*\\(" +
                    "|\\bIF\\s*\\(\\s*NOW\\s*\\(" +
                    // 진법 변환 / CHR 기반
                    "|\\bCHR\\s*\\(" +
                    "|\\bCHAR\\s*\\(\\s*\\d" +
                    // 인코딩된 따옴표 (sqlmap 기본 페이로드)
                    "|%2527|%2522" +
                    ")"
    );

    /**
     * 이메일 형식 검증 (정규식만)
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) return false;
        if (email.length() > MAX_EMAIL_LENGTH) return false;
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * SQL Injection 공격 페이로드 시그니처 포함 여부
     */
    public static boolean containsSqlInjectionPattern(String input) {
        if (input == null || input.isBlank()) return false;
        return SQL_INJECTION_PATTERN.matcher(input).find();
    }

    /**
     * 이메일을 보안 관점에서 종합 검증
     * - 형식 위반 또는 공격 페이로드 포함 시 IllegalArgumentException
     * - GlobalExceptionHandler가 400으로 변환
     *
     * @throws IllegalArgumentException 비정상 입력
     */
    public static void validateEmailOrThrow(String email) {
        if (containsSqlInjectionPattern(email)) {
            throw new IllegalArgumentException("허용되지 않는 문자가 포함되어 있습니다.");
        }
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("올바른 이메일 형식이 아닙니다.");
        }
    }
}
