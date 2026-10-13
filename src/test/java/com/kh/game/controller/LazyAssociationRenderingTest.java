package com.kh.game.controller;

import com.kh.game.entity.Badge;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import com.kh.game.repository.BadgeRepository;
import com.kh.game.repository.GenreRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.repository.SongRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Song.genre·Member.selectedBadge 를 LAZY 로 바꾼 뒤(【확인 43】 Part 1) 화면·API 가 전과 같이 그려지는지 고정한다.
 *
 * 일부러 @Transactional 을 붙이지 않는다 — 테스트 트랜잭션이 세션을 붙들고 있으면 LAZY 가 항상 성공해
 * "요청 밖에서 초기화되는가" 를 검사하지 못한다. 요청 단위 세션(OSIV, 기본 true)만으로 렌더링돼야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
@DisplayName("LAZY 전환 뒤 렌더링 — 관리자 곡/정답 목록 장르, 랭킹 API 뱃지")
class LazyAssociationRenderingTest {

    private static final String PREFIX = "np1-";

    @Autowired MockMvc mockMvc;
    @Autowired SongRepository songRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired BadgeRepository badgeRepository;

    private final List<Song> songs = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();
    private Genre genre;
    private Badge badge;

    @BeforeEach
    void setUp() {
        genre = new Genre();
        genre.setCode(PREFIX + "GENRE");
        genre.setName(PREFIX + "장르명");
        genre.setUseYn("Y");
        genre = genreRepository.save(genre);

        for (int i = 0; i < 3; i++) {
            Song s = new Song();
            s.setTitle(PREFIX + "곡" + i);
            s.setArtist(PREFIX + "가수");
            s.setGenre(genre);
            s.setUseYn("Y");
            s.setIsSolo(true);
            s.setReleaseYear(2020);
            songs.add(songRepository.save(s));
        }

        badge = new Badge();
        badge.setCode(PREFIX + "BADGE");
        badge.setName(PREFIX + "뱃지명");
        badge.setEmoji("🧪");
        badge.setCategory(Badge.BadgeCategory.SCORE);
        badge.setRarity(Badge.BadgeRarity.COMMON);
        badge = badgeRepository.save(badge);

        Member m = new Member();
        m.setEmail(PREFIX + "ranker@test.local");
        m.setPassword("x");
        m.setNickname(PREFIX + "랭커");
        m.setUsername(PREFIX + "ranker");
        m.setStatus(Member.MemberStatus.ACTIVE);
        m.setGuessGames(5);
        m.setGuessScore(999_999);   // 랭킹 1위로 올려 limit 안에 들게 한다
        m.setSelectedBadge(badge);
        members.add(memberRepository.save(m));
    }

    @AfterEach
    void tearDown() {
        songRepository.deleteAll(songs);
        genreRepository.delete(genre);
        memberRepository.deleteAll(members);
        badgeRepository.delete(badge);
    }

    @Test
    @DisplayName("관리자 곡 목록: 장르 열이 그려진다 (LAZY + fetch join, 요청 세션 안)")
    void adminSongList_rendersGenreName() throws Exception {
        mockMvc.perform(get("/admin/song/content").param("keyword", PREFIX + "곡").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PREFIX + "장르명")));
    }

    @Test
    @DisplayName("관리자 정답 목록: 장르 열이 그려진다 (LAZY + @EntityGraph)")
    void adminAnswerList_rendersGenreName() throws Exception {
        mockMvc.perform(get("/admin/answer/content").param("keyword", PREFIX + "곡").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PREFIX + "장르명")));
    }

    @Test
    @DisplayName("랭킹 API: 1위 회원의 뱃지 이모지·이름이 실린다 (LAZY + LEFT JOIN FETCH)")
    void rankingApi_includesBadgeOfTopMember() throws Exception {
        mockMvc.perform(get("/api/ranking").param("mode", "guess").param("type", "score").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nickname").value(PREFIX + "랭커"))
                .andExpect(jsonPath("$[0].badgeEmoji").value("🧪"))
                .andExpect(jsonPath("$[0].badgeName").value(PREFIX + "뱃지명"));
    }
}
