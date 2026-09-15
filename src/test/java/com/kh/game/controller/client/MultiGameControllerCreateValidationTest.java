package com.kh.game.controller.client;

import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방 생성 본문은 서버에서도 검증되어야 한다.
 *
 * 배경: 검증이 클라이언트(select 옵션·maxlength)에만 있어 API 로 직접 보내면 빈 이름·50자 초과(엔티티 제한) 는
 * DB 오류로 500, `maxPlayers: null` 은 언박싱 NPE 로 500, 인원 1명·라운드 0 같은 값은 그대로 저장됐다
 * (2026-09-16 발견). 허용 범위는 생성 화면이 제공하는 값 기준: 이름 2~30자, 인원 2~10, 라운드 1~20.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameController - 방 생성 서버 검증")
class MultiGameControllerCreateValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    private Member host;

    @BeforeEach
    void setUp() {
        host = new Member();
        host.setUsername("create_host");
        host.setNickname("create_host");
        host.setEmail("create_host@test.com");
        host.setPassword("encoded");
        host.setRole(Member.MemberRole.USER);
        host.setStatus(Member.MemberStatus.ACTIVE);
        host = memberRepository.save(host);
    }

    private void expectRejected(String body) throws Exception {
        long before = gameRoomRepository.count();
        mockMvc.perform(post("/game/multi/create")
                        .with(user(new CustomUserDetails(host)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(gameRoomRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest(name = "이름 ''{0}'' 은 거부된다")
    @CsvSource({
            "''",
            "'   '",
            "'a'",
            "'0123456789012345678901234567890'"   // 31자
    })
    void invalidRoomName_isRejected(String roomName) throws Exception {
        expectRejected("{\"roomName\":\"" + roomName + "\",\"maxPlayers\":4,\"totalRounds\":5,\"gameMode\":\"RANDOM\"}");
    }

    @ParameterizedTest(name = "인원 {0} · 라운드 {1} 은 거부된다")
    @CsvSource({
            "1, 5",
            "11, 5",
            "4, 0",
            "4, 21"
    })
    void outOfRangePlayersOrRounds_isRejected(int maxPlayers, int totalRounds) throws Exception {
        expectRejected("{\"roomName\":\"valid room\",\"maxPlayers\":" + maxPlayers
                + ",\"totalRounds\":" + totalRounds + ",\"gameMode\":\"RANDOM\"}");
    }

    @Test
    @DisplayName("maxPlayers 가 null 이면 500 이 아니라 거부 응답이다")
    void nullMaxPlayers_isRejectedNot500() throws Exception {
        expectRejected("{\"roomName\":\"valid room\",\"maxPlayers\":null,\"totalRounds\":5,\"gameMode\":\"RANDOM\"}");
    }

    @Test
    @DisplayName("이름 앞뒤 공백은 잘라서 저장한다")
    void roomName_isTrimmed() throws Exception {
        mockMvc.perform(post("/game/multi/create")
                        .with(user(new CustomUserDetails(host)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomName\":\"  trimmed  \",\"maxPlayers\":4,\"totalRounds\":5,\"gameMode\":\"RANDOM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(gameRoomRepository.findAll())
                .extracting(r -> r.getRoomName())
                .contains("trimmed");
    }
}
