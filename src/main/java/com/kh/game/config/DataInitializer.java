package com.kh.game.config;

import com.kh.game.entity.Badge;
import com.kh.game.entity.BadWord;
import com.kh.game.entity.FanChallengeDifficulty;
import com.kh.game.entity.FanChallengeRecord;
import com.kh.game.entity.FanChallengeStageConfig;
import com.kh.game.entity.Member;
import com.kh.game.repository.BadgeRepository;
import com.kh.game.repository.BadWordRepository;
import com.kh.game.repository.FanChallengeRecordRepository;
import com.kh.game.repository.FanChallengeStageConfigRepository;
import com.kh.game.repository.MemberRepository;
import com.kh.game.service.BadWordService;
import com.kh.game.service.MenuConfigService;
import com.kh.game.service.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final BadWordRepository badWordRepository;
    private final BadWordService badWordService;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final BadgeRepository badgeRepository;
    private final FanChallengeRecordRepository fanChallengeRecordRepository;
    private final FanChallengeStageConfigRepository fanChallengeStageConfigRepository;
    private final SongService songService;
    private final MenuConfigService menuConfigService;
    private final Environment environment;

    private boolean isProdProfile() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Override
    public void run(String... args) {
        initAdminAccount();
        initBadWords();
        initBadges();
        initMenuConfig();
        initFanChallengeStageConfig();
        initFanChallengeTestData();
    }

    /**
     * 홈 메뉴 설정 초기화
     */
    private void initMenuConfig() {
        menuConfigService.initializeDefaultMenus();
        log.info("홈 메뉴 설정 초기화 완료");
    }

    /**
     * 기본 관리자 계정 생성 (dev 프로파일 전용)
     * - prod에서는 자동 생성 차단 (운영자가 직접 admin 계정 생성 필요)
     * - 비밀번호는 로그에 출력하지 않음 (로그 유출 시 즉시 권한 탈취 가능)
     */
    private void initAdminAccount() {
        if (isProdProfile()) {
            log.info("[DataInitializer] prod 프로파일: 기본 관리자 계정 자동 생성을 건너뜁니다.");
            return;
        }

        String adminEmail = "a@a.com";

        if (memberRepository.findByEmail(adminEmail).isPresent()) {
            log.debug("[DataInitializer] 기본 관리자 계정이 이미 존재합니다.");
            return;
        }

        Member admin = new Member();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode("1234"));
        admin.setNickname("관리자");
        admin.setUsername("admin");
        admin.setRole(Member.MemberRole.ADMIN);
        admin.setStatus(Member.MemberStatus.ACTIVE);

        memberRepository.save(admin);
        log.info("[DataInitializer] 기본 관리자 계정 생성 완료 (dev 전용 — 비밀번호는 application-dev.yml 참고)");
    }

    private void initBadWords() {
        if (badWordRepository.count() > 0) {
            log.info("금지어 데이터가 이미 존재합니다. 초기화 건너뜀.");
            return;
        }

        log.info("금지어 초기 데이터 등록 시작...");

        // 일반적인 비속어 및 욕설 목록
        List<String> badWords = Arrays.asList(
            // 기본 욕설
            "시발", "씨발", "ㅅㅂ", "ㅆㅂ", "씹", "좆", "ㅈㄹ", "지랄",
            "병신", "ㅂㅅ", "븅신", "빙신",
            "개새끼", "개새", "개색", "개섀끼",
            "미친놈", "미친년", "미친새끼",
            "닥쳐", "꺼져", "죽어",

            // 변형 욕설
            "시바", "씨바", "씨팔", "시팔", "씨빨", "시빨",
            "ㅗ", "ㅗㅗ",

            // 비하/혐오 표현
            "장애인", "찐따", "ㅉㄸ",
            "한남", "한녀", "김치녀", "김치남",

            // 성적 비속어
            "보지", "자지", "섹스", "sex",

            // 영어 욕설
            "fuck", "shit", "damn", "ass", "bitch",
            "f*ck", "sh*t", "b*tch",

            // 기타 부적절한 표현
            "ㄲㅈ", "꺼지", "닥쳐라", "입닥쳐"
        );

        int count = 0;
        for (String word : badWords) {
            try {
                if (!badWordRepository.existsByWord(word.toLowerCase())) {
                    BadWord badWord = new BadWord(word.toLowerCase());
                    badWordRepository.save(badWord);
                    count++;
                }
            } catch (Exception e) {
                log.warn("금지어 등록 실패: {} - {}", word, e.getMessage());
            }
        }

        // 캐시 리로드
        badWordService.reloadCache();

        log.info("금지어 초기 데이터 등록 완료: {}개", count);
    }

    /**
     * 초기 뱃지 데이터 등록 (23개)
     */
    private void initBadges() {
        if (badgeRepository.count() > 0) {
            log.info("뱃지 데이터가 이미 존재합니다. 초기화 건너뜀.");
            return;
        }

        log.info("뱃지 초기 데이터 등록 시작...");

        // 뱃지 데이터 정의 (code, name, description, emoji, category, rarity, sortOrder)
        List<Object[]> badges = Arrays.asList(
            // 입문 (BEGINNER) - 3개
            new Object[]{"FIRST_GUESS_GAME", "첫 발걸음", "첫 솔로 게임 플레이", "👣", Badge.BadgeCategory.BEGINNER, Badge.BadgeRarity.COMMON, 1},
            new Object[]{"FIRST_CORRECT", "첫 정답", "첫 번째 정답 맞추기", "🎯", Badge.BadgeCategory.BEGINNER, Badge.BadgeRarity.COMMON, 2},
            new Object[]{"MULTI_SPROUT", "멀티게임 새싹", "첫 멀티게임 참여", "🌱", Badge.BadgeCategory.BEGINNER, Badge.BadgeRarity.COMMON, 3},

            // 점수 (SCORE) - 4개
            new Object[]{"SCORE_100", "100점 돌파", "누적 100점 달성", "💯", Badge.BadgeCategory.SCORE, Badge.BadgeRarity.COMMON, 10},
            new Object[]{"SCORE_1000", "1000점 클럽", "누적 1,000점 달성", "🏅", Badge.BadgeCategory.SCORE, Badge.BadgeRarity.RARE, 11},
            new Object[]{"SCORE_5000", "5000점 마스터", "누적 5,000점 달성", "🏆", Badge.BadgeCategory.SCORE, Badge.BadgeRarity.EPIC, 12},
            new Object[]{"SCORE_10000", "만점왕", "누적 10,000점 달성", "👑", Badge.BadgeCategory.SCORE, Badge.BadgeRarity.LEGENDARY, 13},

            // 승리 (VICTORY) - 4개
            new Object[]{"FIRST_MULTI_WIN", "첫 승리", "멀티게임 첫 1등", "🥇", Badge.BadgeCategory.VICTORY, Badge.BadgeRarity.COMMON, 20},
            new Object[]{"MULTI_WINNER_10", "10승 달성", "멀티게임 10회 1등", "🎖️", Badge.BadgeCategory.VICTORY, Badge.BadgeRarity.RARE, 21},
            new Object[]{"MULTI_WINNER_50", "50승 전사", "멀티게임 50회 1등", "⚔️", Badge.BadgeCategory.VICTORY, Badge.BadgeRarity.EPIC, 22},
            new Object[]{"MULTI_WINNER_100", "백전백승", "멀티게임 100회 1등", "🏰", Badge.BadgeCategory.VICTORY, Badge.BadgeRarity.LEGENDARY, 23},

            // 연속 (STREAK) - 4개
            new Object[]{"STREAK_5", "5연속 정답", "5문제 연속 정답", "🔥", Badge.BadgeCategory.STREAK, Badge.BadgeRarity.COMMON, 30},
            new Object[]{"STREAK_10", "10연속 정답", "10문제 연속 정답", "💥", Badge.BadgeCategory.STREAK, Badge.BadgeRarity.RARE, 31},
            new Object[]{"STREAK_20", "음악 천재", "20문제 연속 정답", "🧠", Badge.BadgeCategory.STREAK, Badge.BadgeRarity.EPIC, 32},
            new Object[]{"PERFECT_GAME", "퍼펙트 게임", "10라운드 이상 100% 정답률", "✨", Badge.BadgeCategory.STREAK, Badge.BadgeRarity.EPIC, 33},

            // 티어 (TIER) - 8개
            new Object[]{"TIER_SILVER", "실버 달성", "통합 티어 실버 달성", "🥈", Badge.BadgeCategory.TIER, Badge.BadgeRarity.COMMON, 40},
            new Object[]{"TIER_GOLD", "골드 달성", "통합 티어 골드 달성", "🥇", Badge.BadgeCategory.TIER, Badge.BadgeRarity.RARE, 41},
            new Object[]{"TIER_PLATINUM", "플래티넘 달성", "통합 티어 플래티넘 달성", "💎", Badge.BadgeCategory.TIER, Badge.BadgeRarity.RARE, 42},
            new Object[]{"TIER_DIAMOND", "다이아몬드 달성", "통합 티어 다이아몬드 달성", "💠", Badge.BadgeCategory.TIER, Badge.BadgeRarity.EPIC, 43},
            new Object[]{"TIER_MASTER", "마스터 달성", "통합 티어 마스터 달성", "🔱", Badge.BadgeCategory.TIER, Badge.BadgeRarity.LEGENDARY, 44},
            new Object[]{"MULTI_TIER_GOLD", "멀티 골드", "멀티 티어 골드 달성", "🏅", Badge.BadgeCategory.TIER, Badge.BadgeRarity.RARE, 50},
            new Object[]{"MULTI_TIER_DIAMOND", "멀티 다이아", "멀티 티어 다이아몬드 달성", "💎", Badge.BadgeCategory.TIER, Badge.BadgeRarity.EPIC, 51},
            new Object[]{"MULTI_TIER_CHALLENGER", "챌린저", "멀티 티어 챌린저 달성", "⚡", Badge.BadgeCategory.TIER, Badge.BadgeRarity.LEGENDARY, 52},

            // 팬챌린지 퍼펙트 마일스톤 (SPECIAL) - 6개
            new Object[]{"FAN_FIRST_PERFECT", "첫 퍼펙트", "첫 아티스트 퍼펙트 클리어", "⭐", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.RARE, 60},
            new Object[]{"FAN_PERFECT_5", "퍼펙트 수집가", "5개 아티스트 퍼펙트 클리어", "🌟", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.EPIC, 61},
            new Object[]{"FAN_PERFECT_10", "퍼펙트 마스터", "10개 아티스트 퍼펙트 클리어", "💫", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.LEGENDARY, 62},
            new Object[]{"FAN_HARDCORE_FIRST", "하드코어 정복자", "첫 하드코어 퍼펙트 클리어", "🔥", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.EPIC, 63},
            new Object[]{"FAN_HARDCORE_5", "하드코어 마스터", "5개 아티스트 하드코어 퍼펙트", "💥", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.LEGENDARY, 64},
            new Object[]{"FAN_HARDCORE_10", "하드코어 레전드", "10개 아티스트 하드코어 퍼펙트", "👑", Badge.BadgeCategory.SPECIAL, Badge.BadgeRarity.LEGENDARY, 65}
        );

        int count = 0;
        for (Object[] badgeData : badges) {
            try {
                Badge badge = new Badge();
                badge.setCode((String) badgeData[0]);
                badge.setName((String) badgeData[1]);
                badge.setDescription((String) badgeData[2]);
                badge.setEmoji((String) badgeData[3]);
                badge.setCategory((Badge.BadgeCategory) badgeData[4]);
                badge.setRarity((Badge.BadgeRarity) badgeData[5]);
                badge.setSortOrder((Integer) badgeData[6]);
                badge.setIsActive(true);

                badgeRepository.save(badge);
                count++;
            } catch (Exception e) {
                log.warn("뱃지 등록 실패: {} - {}", badgeData[0], e.getMessage());
            }
        }

        log.info("뱃지 초기 데이터 등록 완료: {}개", count);
    }

    /**
     * 팬 챌린지 단계 설정 초기화
     */
    private void initFanChallengeStageConfig() {
        if (fanChallengeStageConfigRepository.count() > 0) {
            log.info("팬 챌린지 단계 설정이 이미 존재합니다. 초기화 건너뜀.");
            return;
        }

        log.info("팬 챌린지 단계 설정 초기화 시작...");

        // 기본 단계 설정 (1단계만 활성화)
        List<Object[]> stages = Arrays.asList(
            new Object[]{1, 20, "1단계", "🥉", true},
            new Object[]{2, 25, "2단계", "🥈", false},
            new Object[]{3, 30, "3단계", "🥇", false}
        );

        int count = 0;
        for (Object[] stageData : stages) {
            try {
                FanChallengeStageConfig config = new FanChallengeStageConfig();
                config.setStageLevel((Integer) stageData[0]);
                config.setRequiredSongs((Integer) stageData[1]);
                config.setStageName((String) stageData[2]);
                config.setStageEmoji((String) stageData[3]);
                config.setIsActive((Boolean) stageData[4]);
                if ((Boolean) stageData[4]) {
                    config.setActivatedAt(java.time.LocalDateTime.now());
                }

                fanChallengeStageConfigRepository.save(config);
                count++;
            } catch (Exception e) {
                log.warn("단계 설정 등록 실패: {} - {}", stageData[0], e.getMessage());
            }
        }

        log.info("팬 챌린지 단계 설정 초기화 완료: {}개", count);
    }

    /**
     * 팬 챌린지 테스트 데이터 등록 (dev 프로파일 전용 — 실제 DB의 아티스트 사용)
     * - 테스트 멤버(test1@~test6@)와 더미 랭킹 레코드를 생성하므로 운영에선 차단
     */
    private void initFanChallengeTestData() {
        if (isProdProfile()) {
            log.info("[DataInitializer] prod 프로파일: 팬 챌린지 테스트 데이터 생성을 건너뜁니다.");
            return;
        }

        if (fanChallengeRecordRepository.count() > 0) {
            log.info("팬 챌린지 데이터가 이미 존재합니다. 초기화 건너뜀.");
            return;
        }

        // 실제 DB의 아티스트 목록 조회
        List<Map<String, Object>> artistsWithCount = songService.getArtistsWithCount();
        if (artistsWithCount.isEmpty()) {
            log.info("등록된 곡이 없어 팬 챌린지 테스트 데이터 생성 건너뜀.");
            return;
        }

        log.info("팬 챌린지 테스트 데이터 등록 시작... (아티스트 {}개 발견)", artistsWithCount.size());

        // 테스트 유저 생성
        List<Member> testMembers = new java.util.ArrayList<>();
        String[] nicknames = {"음악천재", "노래왕", "멜로디", "리듬마스터", "뮤직러버", "사운드킹"};

        for (int i = 0; i < nicknames.length; i++) {
            final String email = "test" + (i + 1) + "@test.com";
            final String nickname = nicknames[i];
            final String username = "testuser" + (i + 1);

            Member member = memberRepository.findByEmail(email).orElseGet(() -> {
                Member m = new Member();
                m.setEmail(email);
                m.setPassword(passwordEncoder.encode("1234"));
                m.setNickname(nickname);
                m.setUsername(username);
                m.setRole(Member.MemberRole.USER);
                m.setStatus(Member.MemberStatus.ACTIVE);
                return memberRepository.save(m);
            });
            testMembers.add(member);
        }

        int count = 0;
        int maxArtists = Math.min(artistsWithCount.size(), 10); // 최대 10개 아티스트

        for (int i = 0; i < maxArtists; i++) {
            Map<String, Object> artistInfo = artistsWithCount.get(i);
            String artist = (String) artistInfo.get("name");
            int songCount = ((Number) artistInfo.get("count")).intValue();

            if (songCount < 1) continue;

            try {
                // 1위: 퍼펙트 클리어 (가장 빠른 시간)
                int member1Idx = i % testMembers.size();
                FanChallengeRecord record1 = new FanChallengeRecord(
                    testMembers.get(member1Idx), artist, songCount, FanChallengeDifficulty.HARDCORE);
                record1.setCorrectCount(songCount);
                record1.setIsPerfectClear(true);
                record1.setBestTimeMs(30000L + (i * 3000L)); // 30초 ~ 57초
                record1.setAchievedAt(java.time.LocalDateTime.now().minusDays(i + 1));
                fanChallengeRecordRepository.save(record1);
                count++;

                // 2위: 퍼펙트 클리어 (더 느린 시간) - 동점자 테스트용
                int member2Idx = (i + 1) % testMembers.size();
                FanChallengeRecord record2 = new FanChallengeRecord(
                    testMembers.get(member2Idx), artist, songCount, FanChallengeDifficulty.HARDCORE);
                record2.setCorrectCount(songCount);
                record2.setIsPerfectClear(true);
                record2.setBestTimeMs(35000L + (i * 3000L)); // 35초 ~ 62초 (1위보다 5초 느림)
                record2.setAchievedAt(java.time.LocalDateTime.now().minusDays(i + 2));
                fanChallengeRecordRepository.save(record2);
                count++;

                // 3위: 일부만 맞춤
                if (songCount > 2) {
                    int member3Idx = (i + 2) % testMembers.size();
                    FanChallengeRecord record3 = new FanChallengeRecord(
                        testMembers.get(member3Idx), artist, songCount, FanChallengeDifficulty.HARDCORE);
                    record3.setCorrectCount(songCount - 2);
                    record3.setIsPerfectClear(false);
                    record3.setAchievedAt(java.time.LocalDateTime.now().minusDays(i + 3));
                    fanChallengeRecordRepository.save(record3);
                    count++;
                }
            } catch (Exception e) {
                log.warn("팬 챌린지 데이터 등록 실패 ({}): {}", artist, e.getMessage());
            }
        }

        log.info("팬 챌린지 테스트 데이터 등록 완료: {}개", count);
    }
}
