package com.kh.game.repository;

import com.kh.game.entity.Badge;
import com.kh.game.entity.Genre;
import com.kh.game.entity.Member;
import com.kh.game.entity.Song;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N+1 재현·회귀 테스트 (career 【확인 43】 Part 1).
 *
 * 목록 1번 + 행마다(정확히는 1차 캐시 때문에 "서로 다른 연관 수"만큼) 추가 SELECT 가 나가는지를
 * Hibernate Statistics 의 PreparedStatement 수로 단언한다.
 * - 관리자 곡 목록: 목록 1 + count 1 = 2 를 넘으면 N+1 (Song.genre)
 * - 랭킹 첫 목록:   목록 1 을 넘으면 N+1 (Member.selectedBadge)
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("N+1 쿼리 수 — 곡 목록(장르)·랭킹(뱃지)")
class NPlusOneQueryCountTest {

    private static final int GENRES = 5;
    private static final int SONGS = 20;
    private static final int BADGES = 4;
    private static final int MEMBERS = 12;

    @Autowired EntityManager em;
    @Autowired SongRepository songRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired BadgeRepository badgeRepository;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        List<Genre> genres = new ArrayList<>();
        for (int i = 0; i < GENRES; i++) {
            Genre g = new Genre();
            g.setCode("G" + i);
            g.setName("장르" + i);
            g.setUseYn("Y");
            genres.add(genreRepository.save(g));
        }
        for (int i = 0; i < SONGS; i++) {
            Song s = new Song();
            s.setTitle("곡" + i);
            s.setArtist("가수" + (i % 3));
            s.setGenre(genres.get(i % GENRES));   // 장르 5종을 돌아가며 배정
            s.setUseYn("Y");
            s.setIsSolo(true);
            s.setReleaseYear(2020);
            songRepository.save(s);
        }
        // 장르 없는 곡 1건 — LEFT JOIN 이어야 목록에서 빠지지 않는다 (AC [예외])
        Song noGenre = new Song();
        noGenre.setTitle("장르없음");
        noGenre.setArtist("가수X");
        noGenre.setUseYn("Y");
        songRepository.save(noGenre);

        List<Badge> badges = new ArrayList<>();
        for (int i = 0; i < BADGES; i++) {
            Badge b = new Badge();
            b.setCode("B" + i);
            b.setName("뱃지" + i);
            b.setEmoji("🏅");
            b.setCategory(Badge.BadgeCategory.SCORE);
            b.setRarity(Badge.BadgeRarity.COMMON);
            badges.add(badgeRepository.save(b));
        }
        for (int i = 0; i < MEMBERS; i++) {
            Member m = new Member();
            m.setEmail("m" + i + "@test.local");
            m.setPassword("x");
            m.setNickname("회원" + i);
            m.setUsername("user" + i);
            m.setStatus(Member.MemberStatus.ACTIVE);
            m.setGuessGames(1 + i);
            m.setGuessScore(100 * (i + 1));
            // 마지막 1명은 뱃지 없음 — LEFT JOIN 이어야 랭킹에서 빠지지 않는다 (AC [예외])
            m.setSelectedBadge(i == MEMBERS - 1 ? null : badges.get(i % BADGES));
            memberRepository.save(m);
        }

        em.flush();
        em.clear();   // 1차 캐시 비움 — 여기서부터 나가는 SQL 만 센다

        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Nested
    @DisplayName("관리자 곡 목록 — Song.genre")
    class AdminSongList {

        @Test
        @DisplayName("searchWithFilters 20건(장르 5종): SQL 은 목록 1 + count 1 = 2 번")
        void searchWithFilters_isTwoStatements() {
            Page<Song> page = songRepository.searchWithFilters(null, null, null, null, null, null, PageRequest.of(0, 20));
            page.getContent().forEach(s -> { if (s.getGenre() != null) s.getGenre().getName(); });

            assertThat(page.getContent()).hasSize(20);
            assertThat(statistics.getPrepareStatementCount())
                    .as("목록 1 + count 1 을 넘으면 N+1 (장르 수만큼 추가 SELECT)")
                    .isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("searchWithFiltersMultipleArtists 20건: SQL 은 2 번")
        void searchWithFiltersMultipleArtists_isTwoStatements() {
            Page<Song> page = songRepository.searchWithFiltersMultipleArtists(
                    null, List.of("가수0", "가수1", "가수2", "가수X"), null, null, null, null, PageRequest.of(0, 20));
            page.getContent().forEach(s -> { if (s.getGenre() != null) s.getGenre().getName(); });

            assertThat(page.getContent()).hasSize(20);
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("findAll(pageable) 20건 (관리자 정답 목록): SQL 은 2 번")
        void findAllPageable_isTwoStatements() {
            Page<Song> page = songRepository.findAll(PageRequest.of(0, 20));
            page.getContent().forEach(s -> { if (s.getGenre() != null) s.getGenre().getName(); });

            assertThat(page.getContent()).hasSize(20);
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("findByTitleContainingOrArtistContaining (관리자 정답 검색): SQL 은 2 번")
        void searchByKeyword_isTwoStatements() {
            Page<Song> page = songRepository.findByTitleContainingOrArtistContaining("곡", "곡", PageRequest.of(0, 20));
            page.getContent().forEach(s -> { if (s.getGenre() != null) s.getGenre().getName(); });

            assertThat(page.getContent()).hasSize(20);
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("장르 없는 곡도 목록·총건수에 포함된다 (LEFT JOIN)")
        void songWithoutGenre_isStillListed() {
            Page<Song> page = songRepository.searchWithFilters(null, null, null, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getTotalElements()).isEqualTo(SONGS + 1);
            assertThat(page.getContent()).extracting(Song::getTitle).contains("장르없음");
        }
    }

    @Nested
    @DisplayName("랭킹 첫 목록 — Member.selectedBadge")
    class Ranking {

        @Test
        @DisplayName("findTopGuessRankingByScore 12명(뱃지 4종): SQL 은 목록 1 번")
        void guessRankingByScore_isOneStatement() {
            List<Member> top = memberRepository.findTopGuessRankingByScore(PageRequest.of(0, 20));
            top.forEach(Member::getSelectedBadgeEmoji);

            assertThat(top).hasSize(MEMBERS);
            assertThat(statistics.getPrepareStatementCount())
                    .as("목록 1 을 넘으면 N+1 (뱃지 수만큼 추가 SELECT)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("뱃지 없는 회원도 랭킹에 포함되고 점수 내림차순이 유지된다 (LEFT JOIN)")
        void memberWithoutBadge_isStillRanked() {
            List<Member> top = memberRepository.findTopGuessRankingByScore(PageRequest.of(0, 20));

            assertThat(top).hasSize(MEMBERS);
            assertThat(top.get(0).getSelectedBadge()).isNull();          // 점수 최고(1200)가 뱃지 없는 회원
            assertThat(top).extracting(Member::getGuessScore).isSortedAccordingTo((a, b) -> b - a);
        }
    }
}
