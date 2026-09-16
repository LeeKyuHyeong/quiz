# 아키텍처 참조 (패키지·서비스·배치·점수표)

> 2026-09-16 `CLAUDE.md`에서 분리. 클래스 목록은 코드에서 `ls`로 확인 가능한 파생 정보라 CLAUDE.md에는 개수와 규칙만 남겼다. 설계 배경·동시성·보안·JPA 패턴의 깊은 설명은 `System.md`. 멀티플레이 상태 머신·나가기 규칙은 변경이 잦아 **CLAUDE.md에만** 둔다(여기 없음).

This is a **multiplayer music guessing game** built with Spring Boot 3.4.1 + Java 17 + MariaDB.

### Layered Architecture

```
Controller (MVC + REST) → Service (Business Logic) → Repository (JPA) → MariaDB
         ↓
    Thymeleaf Templates (server-side rendering)
```

### Package Structure (`com.kh.game`)

- **controller/client/** (13) - User-facing: `HomeController`, `AuthController`, `GameGuessController`, `GameHostController`, `GameFanChallengeController`, `GameGenreChallengeController`, `RetroGameController`, `MultiGameController`, `RankingController`, `SongReportController`, `BoardController`, `StatsController`, `MyPageController`
- **controller/admin/** (25) - Admin panel: `AdminController` (dashboard), `AdminSongController`, `AdminSongPopularityController`, `AdminAnswerController`, `AdminArtistController`, `AdminGenreController`, `AdminBadWordController`, `AdminContentController`, `AdminMenuController`, `AdminMemberController`, `AdminLoginHistoryController`, `AdminRankingController`, `AdminGameHistoryController`, `AdminGameManagementController`, `AdminChallengeController`, `AdminFanChallengeController`, `AdminGenreChallengeController`, `AdminMultiController`, `AdminRoomController`, `AdminChatController`, `AdminSongReportController`, `AdminStatsController`, `AdminBatchController`, `AdminBatchAffectedController`, `AdminSystemController`
- **service/** (23 + `BrevoMailClient`) - Business logic: `GameSessionService`, `MultiGameService`, `GameRoomService`, `GameBroadcastService`, `SongService`, `SongPopularityVoteService`, `SongReportService`, `MemberService`, `MemberSessionService`, `AnswerValidationService`, `YouTubeValidationService`, `BadWordService`, `BoardService`, `WrongAnswerStatsService`, `BatchService`, `GenreService`, `MultiTierService`, `FanChallengeService`, `FanChallengeStageService`, `GenreChallengeService`, `BadgeService`, `MenuConfigService`, `EmailVerificationService`
- **entity/** - JPA `@Entity` 29개: `Member`, `MemberLoginHistory`, `MemberBadge`, `Badge`, `EmailVerification`, `Song`, `SongAnswer`, `SongReport`, `SongPopularityVote`, `Genre`, `GameSession`, `GameRound`, `GameRoundAttempt`, `GameRoom`, `GameRoomParticipant`, `GameRoomChat`, `BadWord`, `BatchConfig`, `BatchExecutionHistory`, `BatchAffectedSong`, `DailyStats`, `Board`, `BoardComment`, `BoardLike`, `FanChallengeRecord`, `FanChallengeStageConfig`, `GenreChallengeRecord`, `RankingHistory`, `MenuConfig` / enum 3개: `MultiTier`, `FanChallengeDifficulty`, `GenreChallengeDifficulty`
- **repository/** - Spring Data JPA repositories
- **batch/** - 24 scheduled batch jobs managed by `BatchScheduler`
- **config/** - `SecurityConfig` (Spring Security 폼 로그인·CSRF·`/admin/**` ROLE_ADMIN·세션 1개 제한), `PasswordEncoderConfig` (BCrypt), `WebConfig` (업로드 리소스 핸들러), `WebSocketConfig` + `WebSocketAuthInterceptor` (STOMP SUBSCRIBE 방 참가자 인가 — 인증은 핸드셰이크가 세션 Principal 을 전파), `SchedulerConfig`, `DataInitializer`
- **security/** - `CustomUserDetailsService`/`CustomUserDetails`, 로그인 성공·실패 핸들러, `LoginRateLimiter` (bucket4j 토큰 버킷, IP별 분당 20회 제한, 화이트리스트 지원)
- **exception/** - `GlobalExceptionHandler` (`@RestControllerAdvice`, REST/MVC 분기 응답), `BusinessException`, `ResourceNotFoundException`
- **util/** - `AnswerGeneratorUtil` (English→Korean phonetic conversion for song titles), `SecurityInputValidator` (이메일 정규식 + SQL Injection 패턴 차단), `JunkInputFilter`
- **dto/** - `GameSettings` (multiplayer room configuration), `WebSocketMessage`

### Game Modes

1. **Solo Guess** - User guesses songs with 3 attempts. Supports various modes (RANDOM, FIXED_GENRE, FIXED_ARTIST, FIXED_YEAR, per-round selection). Includes "30-song Challenge" for ranked play.
2. **Solo Host** - User reads clues for others to guess (100/70/50 points)
3. **Fan Challenge** - Artist-focused challenge (`FanChallengeService.CHALLENGE_SONG_COUNT` = 20곡). Two difficulty levels (NORMAL 7s listen + 6s answer / HARDCORE 5s + 5s, lives 3). NORMAL은 1단계(20곡) 고정, HARDCORE만 단계제(20/25/30곡, `FanChallengeStageConfig`)와 랭킹 반영. Perfect clear tracking, artist-specific rankings.
4. **Genre Challenge** - Genre-focused challenge (`GenreChallengeService` MIN/MAX_SONG_COUNT = 50곡). NORMAL 7s + 6s / HARDCORE 5s + 5s, lives 5, HARDCORE만 랭킹 반영.
5. **Retro Game** - Nostalgia mode: `releaseYear < 2000` 또는 장르 `RETRO` 곡 (`SongRepository`).
6. **Multiplayer** - Room-based game. 서버→클라이언트는 STOMP/SockJS WebSocket push, 액션은 REST POST, 연결 실패 시 polling fallback. First correct answer scores 100 points. LP-based tier system (Bronze→Challenger).

### Key Data Flow

- `GameSession` → contains `GameRound` → tracks `GameRoundAttempt` (solo mode)
- `GameRoom` → has `GameRoomParticipant` → stores `GameRoomChat` (multiplayer mode)
- `Song` → has multiple `SongAnswer` for fuzzy matching validation
- `Board` → has `BoardComment` and `BoardLike` (community board)
- `Member` → has `MemberBadge` → links to `Badge` (achievement system)
- `FanChallengeRecord` → tracks artist challenge attempts with difficulty, stage and score
- `GenreChallengeRecord` → tracks genre challenge attempts with difficulty and score
- `RankingHistory` → stores daily ranking snapshots for historical tracking
- `MenuConfig` → configurable navigation menu items for client UI


### Key Services

- **AnswerValidationService** - Validates user answers with normalization (lowercase, strip spaces/special chars, keep only alphanumeric + Korean), checks both `Song.title` and `SongAnswer` table
- **AnswerGeneratorUtil** - Generates answer variants including English→Korean phonetic conversion using word/phoneme mapping tables (~700 common words)
- **YouTubeValidationService** - Two-phase validation: oEmbed API check → thumbnail size check (detects deleted videos)
- **BadWordService** - Profanity filtering with ConcurrentHashMap cache, auto-reloads on changes
- **SongReportService** - Handles user reports for problematic songs
- **DataInitializer** - CommandLineRunner. `count()==0`일 때 메뉴·금칙어·뱃지·팬 챌린지 단계 설정을 seed. 기본 admin·테스트 데이터는 prod에서 생성하지 않음
- **BoardService** - Community board CRUD with category filtering, comments, and likes
- **MultiTierService** - LP and tier management for multiplayer with ELO-based rating calculations
- **FanChallengeService** / **FanChallengeStageService** - Artist challenge game logic, HARDCORE 단계(20/25/30곡) 설정
- **GenreChallengeService** - Genre challenge game logic (50곡, lives 5)
- **BadgeService** - Achievement badge management with automatic and manual award conditions
- **EmailVerificationService** - 6자리 이메일 인증 코드 발급/검증. 회원가입(미가입 이메일)과 비밀번호 재설정(가입 이메일) 공용, `email_verification` 한 테이블 사용
- **BrevoMailClient** - **Brevo Transactional Email API**(HTTPS) 호출 한 곳. cafe24가 SMTP 포트(25/465/587) outbound를 차단해 Gmail SMTP 대신 도입. `RestClient`로 호출하며 4xx/5xx/네트워크 에러를 분리 처리. 인증 코드·임시 비밀번호 메일이 모두 여기를 거침
- **MemberSessionService** - `SessionRegistry`로 특정 회원의 로그인 세션을 만료. 관리자 세션 강제 종료·비밀번호 초기화·재설정 뒤 호출
- **비밀번호 초기화/재설정** - 비밀번호는 BCrypt 단방향이라 "찾기"는 없다. 관리자 초기화(`/admin/member/reset-password/{id}`)는 12자 임시 비밀번호를 **메일 발송 성공 후에만** 저장(본인 계정 불가). 본인 재설정(`/auth/password-reset`, 로그인 불필요)은 이메일 코드 인증 → 새 비밀번호 저장 → 세션 만료
- **LoginRateLimiter** - IP별 토큰 버킷(bucket4j) 기반 분당 20회 제한. `X-Forwarded-For`/`X-Real-IP` 헤더 인식, 화이트리스트 IP 지원(`security.rate-limit.whitelist`). 로그인/회원가입/이메일 인증 엔드포인트에 적용
- **SecurityInputValidator** - 이메일 정규식 검증 + SQLi 페이로드 패턴 차단(SLEEP, BENCHMARK, DBMS_PIPE, WAITFOR, UNION SELECT, XOR, %2527 등). 위반 시 `IllegalArgumentException` → 400 응답

### Tier System (Multiplayer)

LP-based ranking system similar to League of Legends:
- **Tiers:** Bronze → Silver → Gold → Platinum → Diamond → Master → Challenger
- **LP Range:** 0-100 per tier, promotion/demotion at boundaries
- **ELO Rating:** Combined tier+LP rating for matchmaking calculations
- **LP Changes:** Based on game placement, player count, and opponent tier differential

### Badge System

Achievement badges with categories and rarities:
- **Categories:** BEGINNER, SCORE, VICTORY, STREAK, TIER, SPECIAL
- **Rarities:** COMMON (gray), RARE (blue), EPIC (purple), LEGENDARY (gold)
- Awarded via `BadgeAwardBatch` or directly through `BadgeService`

### Batch Jobs (managed by BatchScheduler)

All 24 batches are DB-configurable via `BatchConfig` table with cron expressions (`BatchService` seeds defaults):
- **Cleanup:** `SessionCleanupBatch`, `GameSessionCleanupBatch`, `RoomCleanupBatch`, `ChatCleanupBatch`, `BoardCleanupBatch`, `LoginHistoryCleanupBatch`, `BatchExecutionHistoryCleanupBatch`, `GameRoundAttemptCleanupBatch`, `SongReportCleanupBatch`
- **Stats & Rankings:** `DailyStatsBatch`, `RankingSnapshotBatch`, `WeeklyRankingResetBatch`, `MonthlyRankingResetBatch`
- **Member Management:** `InactiveMemberBatch`, `BadgeAwardBatch`, `LpDecayBatch`, `LoginStreakBatch`
- **Song Integrity:** `SongFileCheckBatch`, `SongAnalyticsBatch`, `YouTubeVideoCheckBatch`, `DuplicateSongCheckBatch`, `SongAnswerGenerationBatch`
- **Fan Challenge:** `WeeklyPerfectRefreshBatch`
- **System:** `SystemReportBatch`

### Scoring System

**Solo Guess (30곡 도전 모드 — 시간 기반):**

| 답변 시간 | 점수 |
|----------|------|
| 0-5초 | 100점 |
| 5-8초 | 90점 |
| 8-12초 | 80점 |
| 12-15초 | 70점 |
| 15초+ | 60점 |
| 3번 실패 | 0점 |

- **Solo Guess (casual):** 10 → 7 → 5 points (3 attempts)
- **Solo Host:** 100 → 70 → 50 points (3 attempts, host reads clues)
- **Multiplayer:** 100 points for first correct answer

**Fan Challenge 난이도:**

| 설정 | NORMAL | HARDCORE |
|------|--------|----------|
| 노래 재생 | 7초 | 5초 |
| 답변 시간 | 6초 | 5초 |
| 생명 | 3개 | 3개 |
| 초성 힌트 | X | X |
| 랭크 기록 | X | O |

> 값 출처: `FanChallengeDifficulty` enum (NORMAL 7000/6000, HARDCORE 5000/5000, 생명 3, `isShowChosungHint`=false, `isRanked`=HARDCORE만). 난이도는 2단계뿐. 곡 수는 20곡(`CHALLENGE_SONG_COUNT`), HARDCORE 단계는 20/25/30곡.

**Genre Challenge 난이도:**

| 설정 | NORMAL | HARDCORE |
|------|--------|----------|
| 노래 재생 | 7초 | 5초 |
| 답변 시간 | 6초 | 5초 |
| 생명 | 5개 | 5개 |
| 랭크 기록 | X | O |

> 값 출처: `GenreChallengeDifficulty` enum (NORMAL 7000/6000/5/false, HARDCORE 5000/5000/5/true). 곡 수 50곡(`GenreChallengeService` MIN/MAX_SONG_COUNT).

### Community Board

- Categories: REQUEST (곡 추천/요청), OPINION (의견/후기), QUESTION (질문), FREE (자유)
- Features: Comments, likes, view count tracking
- Status: ACTIVE, DELETED, HIDDEN
