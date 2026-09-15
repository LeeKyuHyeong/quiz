package com.kh.game.util;

import java.security.SecureRandom;

/**
 * 관리자 초기화용 임시 비밀번호 생성기.
 * 영문 대소문자·숫자 12자. 메일로 받아 손으로 옮겨 적는 값이라 0/O, 1/l/I 는 뺀다.
 */
public final class TemporaryPasswordGenerator {

    public static final int LENGTH = 12;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
