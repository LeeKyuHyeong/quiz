package com.kh.game.repository;

import com.kh.game.entity.FanChallengeRecord;
import com.kh.game.entity.GameRoom;
import com.kh.game.entity.GameRoomChat;
import com.kh.game.entity.Genre;
import com.kh.game.entity.GenreChallengeRecord;
import com.kh.game.entity.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "오늘" 집계 쿼리의 날짜 경계 테스트.
 *
 * DB 함수(DATE(x)=CURRENT_DATE 등)를 제거하고 Asia/Seoul 기준 파라미터 바인딩으로 바꾼
 * 세 쿼리가 반열림 구간 [start, end)로 정확히 동작하는지(자정 직전/직후 포함) 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("오늘 집계 쿼리 날짜 경계 테스트")
class TodayCountBoundaryTest {

    @Autowired private FanChallengeRecordRepository fanRepository;
    @Autowired private GenreChallengeRecordRepository genreRepository;
    @Autowired private GameRoomChatRepository chatRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GenreRepository genreEntityRepository;
    @Autowired private GameRoomRepository roomRepository;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // UK(member 포함) 충돌을 피하기 위해 레코드마다 새 회원 생성
    private Member newMember(String tag) {
        Member m = new Member();
        m.setEmail(tag + "@test.com");
        m.setUsername("user_" + tag);
        m.setNickname("nick_" + tag);
        m.setPassword("pw");
        return memberRepository.save(m);
    }

    private void saveFanRecord(String tag, LocalDateTime achievedAt) {
        FanChallengeRecord r = new FanChallengeRecord(newMember(tag), "artist_" + tag, 20);
        r.setAchievedAt(achievedAt);
        fanRepository.save(r);
    }

    @Test
    @DisplayName("FanChallenge - 반열림 구간 [start, end): 자정 직전 제외, start 포함, end 제외")
    void fanChallenge_halfOpenBoundary() {
        LocalDate d = LocalDate.of(2026, 3, 15);
        LocalDateTime start = d.atStartOfDay();
        LocalDateTime end = d.plusDays(1).atStartOfDay();

        saveFanRecord("prevDayEnd", start.minusNanos(1000));   // 전날 23:59:59.999999 → 제외
        saveFanRecord("startExact", start);                     // 당일 00:00:00       → 포함
        saveFanRecord("midday", d.atTime(12, 0));               // 당일 12:00          → 포함
        saveFanRecord("dayEnd", end.minusNanos(1000));          // 당일 23:59:59.999999 → 포함
        saveFanRecord("nextDayStart", end);                     // 다음날 00:00:00     → 제외

        assertThat(fanRepository.countByAchievedAtBetween(start, end)).isEqualTo(3);
    }

    @Test
    @DisplayName("GenreChallenge - 반열림 구간 [start, end) 경계 검증")
    void genreChallenge_halfOpenBoundary() {
        Genre genre = new Genre();
        genre.setCode("KPOP");
        genre.setName("K-POP");
        genre.setUseYn("Y");
        genre = genreEntityRepository.save(genre);

        LocalDate d = LocalDate.of(2026, 3, 15);
        LocalDateTime start = d.atStartOfDay();
        LocalDateTime end = d.plusDays(1).atStartOfDay();

        LocalDateTime[] times = {start.minusNanos(1000), start, d.atTime(12, 0), end.minusNanos(1000), end};
        int i = 0;
        for (LocalDateTime t : times) {
            GenreChallengeRecord r = new GenreChallengeRecord(newMember("g" + i++), genre, 20);
            r.setAchievedAt(t);
            genreRepository.save(r);
        }

        assertThat(genreRepository.countByAchievedAtBetween(start, end)).isEqualTo(3);
    }

    @Test
    @DisplayName("GameRoomChat - 하한 경계: 오늘 0시 이후만 집계, 내일 0시 이후는 0")
    void gameRoomChat_lowerBound() {
        // createdAt은 @CreationTimestamp라 값 제어 불가 → 삽입 시점(now, 오늘)으로 채워짐
        Member host = newMember("host");
        GameRoom room = new GameRoom();
        room.setRoomCode("ABC123");
        room.setRoomName("test room");
        room.setHost(host);
        room = roomRepository.save(room);

        chatRepository.save(GameRoomChat.chat(room, host, "hello"));
        chatRepository.save(GameRoomChat.chat(room, host, "world"));

        LocalDate today = LocalDate.now(SEOUL);
        assertThat(chatRepository.countByCreatedAtGreaterThanEqual(today.atStartOfDay())).isEqualTo(2);
        assertThat(chatRepository.countByCreatedAtGreaterThanEqual(today.plusDays(1).atStartOfDay())).isZero();
    }

    @Test
    @DisplayName("countTodayRecords() - Asia/Seoul '오늘'만 집계 (default 메서드 위임 확인)")
    void countTodayRecords_delegatesUsingSeoulToday() {
        LocalDateTime nowSeoul = LocalDateTime.now(SEOUL);
        saveFanRecord("today", nowSeoul);
        saveFanRecord("twoDaysAgo", nowSeoul.minusDays(2));

        assertThat(fanRepository.countTodayRecords()).isEqualTo(1);
    }
}
