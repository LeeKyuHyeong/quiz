package com.kh.game.controller.client;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.Member;
import com.kh.game.repository.MemberRepository;
import com.kh.game.security.CustomUserDetails;
import com.kh.game.service.GameRoomService;
import com.kh.game.service.MultiGameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결과 페이지는 표시할 참가자가 없어도 죽지 않아야 한다.
 *
 * 배경: result.html 이 results[0] 을 무조건 읽는데, getFinalResult 는 LEFT 이면서 0점인 사람을 빼므로
 * 방장 혼자 나가 닫힌 방의 결과 URL(뒤로가기·히스토리)을 열면 빈 목록으로 에러 페이지가 떴다 (2026-09-16 발견).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameController - 결과 페이지")
class MultiGameControllerResultPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private MultiGameService multiGameService;

    @Autowired
    private MemberRepository memberRepository;

    private Member host;
    private GameRoom room;

    @BeforeEach
    void setUp() {
        host = new Member();
        host.setUsername("result_host");
        host.setNickname("result_host");
        host.setEmail("result_host@test.com");
        host.setPassword("encoded");
        host.setRole(Member.MemberRole.USER);
        host.setStatus(Member.MemberStatus.ACTIVE);
        host = memberRepository.save(host);

        // 방장 혼자 만든 방에서 나감 → 방 FINISHED, 방장 LEFT(0점) → 결과 목록이 빈다
        room = gameRoomService.createRoom(host, "result room", 4, 5, false, "{}");
        gameRoomService.leaveRoom(room, host);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.FINISHED);
        assertThat(multiGameService.getFinalResult(room)).isEmpty();
    }

    @Test
    @DisplayName("표시할 참가자가 없으면 에러 페이지 대신 로비로 보낸다")
    void emptyResult_redirectsToLobby() throws Exception {
        mockMvc.perform(get("/game/multi/room/" + room.getRoomCode() + "/result")
                        .with(user(new CustomUserDetails(host))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/game/multi"));
    }
}
