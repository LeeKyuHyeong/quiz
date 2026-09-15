package com.kh.game.controller.client;

import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomParticipant;
import com.kh.game.entity.Member;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 폴링 GET(/round, /chats) 참가자 검사.
 * WebSocket 구독 인가(WebSocketAuthInterceptor)와 같은 기준이 REST 폴백 경로에도 적용되는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameController - 폴링 GET 참가자 검사")
class MultiGameControllerPollingAuthTest {

    private static final String ROOM_CODE = "POLL01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    private Member participant;
    private Member leftMember;
    private Member outsider;

    @BeforeEach
    void setUp() {
        participant = createMember("participant@test.com");
        leftMember = createMember("left@test.com");
        outsider = createMember("outsider@test.com");

        GameRoom room = new GameRoom();
        room.setRoomCode(ROOM_CODE);
        room.setRoomName("polling test room");
        room.setHost(participant);
        room = gameRoomRepository.save(room);

        createParticipant(room, participant, GameRoomParticipant.ParticipantStatus.JOINED);
        createParticipant(room, leftMember, GameRoomParticipant.ParticipantStatus.LEFT);
    }

    private Member createMember(String email) {
        Member m = new Member();
        m.setEmail(email);
        m.setPassword("encoded");
        m.setNickname(email.substring(0, email.indexOf('@')));
        m.setUsername(email.substring(0, email.indexOf('@')));
        m.setRole(Member.MemberRole.USER);
        m.setStatus(Member.MemberStatus.ACTIVE);
        return memberRepository.save(m);
    }

    private void createParticipant(GameRoom room, Member member, GameRoomParticipant.ParticipantStatus status) {
        GameRoomParticipant p = new GameRoomParticipant(room, member);
        p.setStatus(status);
        participantRepository.save(p);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Member member) {
        return request.with(user(new CustomUserDetails(member)));
    }

    @Nested
    @DisplayName("GET /game/multi/room/{code}/round")
    class RoundInfo {

        private MockHttpServletRequestBuilder request() {
            return get("/game/multi/room/{code}/round", ROOM_CODE);
        }

        @Test
        @DisplayName("비로그인 → 401")
        void anonymous_401() throws Exception {
            mockMvc.perform(request())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("미참가 회원 → 403")
        void outsider_403() throws Exception {
            mockMvc.perform(as(request(), outsider))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("LEFT 참가자(강퇴·퇴장) → 403")
        void left_403() throws Exception {
            mockMvc.perform(as(request(), leftMember))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("활성 참가자 → 200, 라운드 정보 반환")
        void participant_200() throws Exception {
            mockMvc.perform(as(request(), participant))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value("WAITING"));
        }
    }

    @Nested
    @DisplayName("GET /game/multi/room/{code}/chats")
    class Chats {

        private MockHttpServletRequestBuilder request() {
            return get("/game/multi/room/{code}/chats", ROOM_CODE).param("lastId", "0");
        }

        @Test
        @DisplayName("비로그인 → 401")
        void anonymous_401() throws Exception {
            mockMvc.perform(request())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("미참가 회원 → 403")
        void outsider_403() throws Exception {
            mockMvc.perform(as(request(), outsider))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("LEFT 참가자(강퇴·퇴장) → 403")
        void left_403() throws Exception {
            mockMvc.perform(as(request(), leftMember))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("활성 참가자 → 200, 채팅 목록 반환")
        void participant_200() throws Exception {
            mockMvc.perform(as(request(), participant))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.chats").isArray());
        }
    }
}
