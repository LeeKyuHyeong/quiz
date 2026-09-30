package com.kh.game.party;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사진 폴더(party.image-dir)를 설정했을 때: 관리자만 사진을 받고, 파일이 있는 사진 문제만 출제된다.
 */
@SpringBootTest(properties = "party.image-dir=target/party-test-images")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("파티 사진 경로 (/admin/party/images/**)")
class PartyImageAccessTest {

    private static final Path DIR = Path.of("target/party-test-images");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartyGameService gameService;
    @Autowired
    private PartyItemRepository partyItemRepository;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve("person_01.jpg"), "image-bytes");
        Files.writeString(DIR.getParent().resolve("outside.txt"), "outside-content");
        gameService.newGame();
    }

    @Test
    @DisplayName("[권한] 사진은 관리자만 받는다")
    void onlyAdminGetsImage() throws Exception {
        mockMvc.perform(get("/admin/party/images/person_01.jpg").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string("image-bytes"));
        mockMvc.perform(get("/admin/party/images/person_01.jpg").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/party/images/person_01.jpg")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("[예외] 없는 사진은 404 다")
    void missingImageIs404() throws Exception {
        mockMvc.perform(get("/admin/party/images/none.jpg").with(user("a").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[권한] 상위 폴더 표기가 든 주소는 폴더 밖 파일을 내주지 않는다 (방화벽이 거부하거나 200 이 아니다)")
    void cannotEscapeFolder() throws Exception {
        for (String path : new String[]{"/admin/party/images/../outside.txt",
                "/admin/party/images/%2e%2e/outside.txt"}) {
            try {
                String body = mockMvc.perform(get(path).with(user("a").roles("ADMIN")))
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).as(path).doesNotContain("outside-content");
            } catch (org.springframework.security.web.firewall.RequestRejectedException rejected) {
                // Spring Security 방화벽이 요청 자체를 거부했다
            }
        }
    }

    @Test
    @DisplayName("[R-036] 파일이 있는 사진 문제만 출제되고, 보드가 받은 주소로 사진이 열린다")
    void picksOnlyImagesThatExist() throws Exception {
        PartyItem ready = new PartyItem();
        ready.setCategory(PartyCategory.PERSON);
        ready.setSubCategory("공통");
        ready.setPresentation(PartyPresentation.IMAGE);
        ready.setAnswer("유재석");
        ready.setImagePath("person_01.jpg");
        partyItemRepository.save(ready);
        PartyItem missing = new PartyItem();
        missing.setCategory(PartyCategory.PERSON);
        missing.setSubCategory("공통");
        missing.setPresentation(PartyPresentation.IMAGE);
        missing.setAnswer("강호동");
        missing.setImagePath("person_02.jpg");
        partyItemRepository.save(missing);

        assertThat(gameService.remaining().get("PERSON")).containsEntry("공통", 1);

        String version = String.valueOf(gameService.boardView().version());
        mockMvc.perform(post("/admin/party/pick").param("version", version).param("category", "PERSON")
                        .with(user("a").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.card.answer").value("유재석"));
        gameService.show();

        String imageUrl = gameService.boardView().item().imageUrl();
        mockMvc.perform(get(imageUrl).with(user("a").roles("ADMIN"))).andExpect(status().isOk());
    }
}
