package com.kh.game.controller.client;

import com.jayway.jsonpath.JsonPath;
import com.kh.game.entity.GameRoom;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방 생성 → 로비 목록 → 코드 참가 흐름의 계약 테스트.
 *
 * 요청 JSON 은 클라이언트(multi-create.js, multi-lobby.js, multi-join.js)가 실제로 보내는 형태를 그대로 쓴다.
 * 배경: 클라이언트가 isPrivate 와 중첩 settings 로 보내고 서버는 privateRoom 과 최상위 필드로 읽어
 * 비공개 플래그와 게임 모드가 조용히 버려지던 결함(2026-09-15 발견). Jackson 이 미지 키를 무시하므로
 * 저장된 값까지 확인해야 잡힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("MultiGameController - 방 생성/목록/참가 계약")
class MultiGameControllerRoomLifecycleTest {

    /** multi-create.js 가 보내는 본문과 같은 형태 (키 이름·평탄화 유지) */
    private static final String CREATE_PRIVATE_FIXED_ARTIST = """
            {
              "roomName": "private test room",
              "maxPlayers": 4,
              "totalRounds": 5,
              "privateRoom": true,
              "gameMode": "FIXED_ARTIST",
              "soloOnly": true,
              "selectedArtists": ["IU", "BTS"]
            }
            """;

    private static final String CREATE_PUBLIC_RANDOM = """
            {
              "roomName": "public test room",
              "maxPlayers": 8,
              "totalRounds": 10,
              "privateRoom": false,
              "gameMode": "RANDOM"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GameRoomRepository gameRoomRepository;

    @Autowired
    private GameRoomParticipantRepository participantRepository;

    private Member host;
    private Member guest;

    @BeforeEach
    void setUp() {
        host = createMember("host@test.com");
        guest = createMember("guest@test.com");
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

    private String createRoom(Member creator, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/game/multi/create")
                        .with(user(new CustomUserDetails(creator)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.roomCode");
    }

    /**
     * 클라이언트 스크립트 ↔ 서버 DTO 계약.
     * 위 픽스처가 JS 와 다시 어긋나도 잡을 수 있도록 실제 multi-create.js 본문을 읽어 확인한다.
     */
    @Nested
    @DisplayName("multi-create.js ↔ GameSettings 계약")
    class ClientContract {

        @Test
        @DisplayName("생성 스크립트는 privateRoom 키를 쓰고 게임 설정을 최상위에 펼쳐 보낸다")
        void createScript_matchesServerDtoFieldNames() throws Exception {
            String js = new String(
                    new org.springframework.core.io.ClassPathResource("static/js/client/multi-create.js")
                            .getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(js).contains("privateRoom: isPrivate");
            assertThat(js).contains("...settings");
            assertThat(js).doesNotContain("isPrivate: isPrivate");
            assertThat(js).doesNotContain("settings: settings");
        }

        @Test
        @DisplayName("픽스처의 모든 키는 GameSettings 의 실제 프로퍼티다 (미지 키는 Jackson 이 조용히 버린다)")
        void fixtureKeys_areRealDtoProperties() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Set<String> dtoProps = mapper.readValue(
                    mapper.writeValueAsString(new com.kh.game.dto.GameSettings()),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}).keySet();

            for (String fixture : new String[]{CREATE_PRIVATE_FIXED_ARTIST, CREATE_PUBLIC_RANDOM}) {
                java.util.Set<String> keys = mapper.readValue(fixture,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}).keySet();
                assertThat(dtoProps).containsAll(keys);
            }
        }
    }

    @Nested
    @DisplayName("POST /game/multi/create")
    class Create {

        @Test
        @DisplayName("클라이언트 본문의 privateRoom 과 게임 모드 설정이 그대로 저장된다")
        void clientPayload_isPersisted() throws Exception {
            String roomCode = createRoom(host, CREATE_PRIVATE_FIXED_ARTIST);

            GameRoom room = gameRoomRepository.findByRoomCode(roomCode).orElseThrow();
            assertThat(room.getIsPrivate()).isTrue();
            assertThat(room.getMaxPlayers()).isEqualTo(4);
            assertThat(room.getTotalRounds()).isEqualTo(5);
            assertThat(room.getSettings())
                    .contains("\"gameMode\":\"FIXED_ARTIST\"")
                    .contains("\"soloOnly\":true")
                    .contains("\"selectedArtists\":[\"IU\",\"BTS\"]")
                    .contains("\"privateRoom\":true");
        }

        @Test
        @DisplayName("방장은 생성 즉시 참가자로 등록된다")
        void host_isParticipant() throws Exception {
            String roomCode = createRoom(host, CREATE_PUBLIC_RANDOM);

            GameRoom room = gameRoomRepository.findByRoomCode(roomCode).orElseThrow();
            assertThat(participantRepository.findByGameRoomAndMember(room, host)).isPresent();
        }
    }

    @Nested
    @DisplayName("GET /game/multi/rooms (로비 목록)")
    class RoomList {

        @Test
        @DisplayName("비공개 방은 목록에서 빠지고 공개 방만 보인다")
        void privateRoom_isHiddenFromList() throws Exception {
            String privateCode = createRoom(host, CREATE_PRIVATE_FIXED_ARTIST);
            String publicCode = createRoom(guest, CREATE_PUBLIC_RANDOM);

            String list = mockMvc.perform(get("/game/multi/rooms"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(list).contains(publicCode).doesNotContain(privateCode);
        }
    }

    @Nested
    @DisplayName("POST /game/multi/join/{code}")
    class Join {

        @Test
        @DisplayName("비공개 방도 코드를 알면 비밀번호 없이 참가된다 (로비/참가 페이지가 보내는 빈 본문)")
        void privateRoom_joinableByCodeWithoutPassword() throws Exception {
            String roomCode = createRoom(host, CREATE_PRIVATE_FIXED_ARTIST);

            mockMvc.perform(post("/game/multi/join/{code}", roomCode)
                            .with(user(new CustomUserDetails(guest)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.roomCode").value(roomCode));

            GameRoom room = gameRoomRepository.findByRoomCode(roomCode).orElseThrow();
            assertThat(participantRepository.findByGameRoomAndMember(room, guest)).isPresent();
        }

        @Test
        @DisplayName("소문자 코드도 대문자로 정규화되어 참가된다")
        void lowercaseCode_isNormalized() throws Exception {
            String roomCode = createRoom(host, CREATE_PUBLIC_RANDOM);

            mockMvc.perform(post("/game/multi/join/{code}", roomCode.toLowerCase())
                            .with(user(new CustomUserDetails(guest)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("비로그인 참가 요청은 거부된다")
        void anonymous_isRejected() throws Exception {
            String roomCode = createRoom(host, CREATE_PUBLIC_RANDOM);

            mockMvc.perform(post("/game/multi/join/{code}", roomCode)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("비공개·비밀번호 검사가 없던 구 엔드포인트 /room/{code}/join 은 제거되어 404")
        void legacyJoinEndpoint_isGone() throws Exception {
            String roomCode = createRoom(host, CREATE_PRIVATE_FIXED_ARTIST);

            mockMvc.perform(post("/game/multi/room/{code}/join", roomCode)
                            .with(user(new CustomUserDetails(guest)))
                            .with(csrf()))
                    .andExpect(status().isNotFound());

            GameRoom room = gameRoomRepository.findByRoomCode(roomCode).orElseThrow();
            assertThat(participantRepository.findByGameRoomAndMember(room, guest)).isEmpty();
        }
    }
}
