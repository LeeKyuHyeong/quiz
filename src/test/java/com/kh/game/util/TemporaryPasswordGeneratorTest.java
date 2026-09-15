package com.kh.game.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    @Test
    @DisplayName("12자, 영문 대소문자·숫자만, 헷갈리는 문자(0 O l 1 I) 제외")
    void generate_hasExpectedShape() {
        for (int i = 0; i < 200; i++) {
            String pw = TemporaryPasswordGenerator.generate();
            assertThat(pw).hasSize(TemporaryPasswordGenerator.LENGTH);
            assertThat(pw).matches("^[A-Za-z0-9]+$");
            assertThat(pw).doesNotContainPattern("[0OlI1]");
        }
    }

    @Test
    @DisplayName("호출마다 다른 값")
    void generate_isRandom() {
        assertThat(TemporaryPasswordGenerator.generate())
                .isNotEqualTo(TemporaryPasswordGenerator.generate());
    }
}
