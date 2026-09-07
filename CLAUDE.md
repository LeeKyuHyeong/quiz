# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 🧭 LLM 코딩 가이드라인 (행동 원칙)

LLM 코딩에서 흔히 발생하는 실수를 줄이기 위한 행동 가이드라인. Andrej Karpathy의 LLM 코딩 함정 관찰을 기반으로 함.

**Tradeoff:** 이 가이드라인은 **속도보다 신중함**에 무게를 둔다. 사소한 작업(오타 수정, 명백한 한 줄 변경)에는 판단껏 유연하게 적용할 것.

### 1. Think Before Coding (코딩 전에 먼저 생각)

**가정하지 마라. 혼란을 숨기지 마라. Tradeoff를 드러내라.**

구현에 들어가기 전에:

- **가정을 명시적으로 진술**한다. 불확실하면 추측하지 말고 질문한다.
- **여러 해석이 가능하면 모두 제시**한다. 조용히 하나만 골라서 진행하지 않는다.
- **더 단순한 접근이 존재하면 말한다.** 필요할 때는 반박(push back)한다.
- **불명확하면 멈춘다.** 무엇이 헷갈리는지 명시하고 묻는다.

**이 프로젝트에서의 적용 예시:**

- **멀티플레이어 상태 머신 작업 시:** `GameRoom`은 `WAITING → PREPARING → PLAYING → ENDED` 단계로 흐른다. 한 phase 처리만 고치라는 요청이라도 `GameRoomService` + 폴링 로직(`/round-ready` 등) + 클라이언트 JS가 동시에 영향. 어디까지 영향이 가는지 먼저 확인한다.
- **AnswerValidation 작업 시:** `AnswerValidationService`(정규화) + `AnswerGeneratorUtil`(영→한 음역 변환) + `SongAnswer` 테이블(수동 정답)이 3중으로 얽혀 있음. "답이 인정 안 돼요"라는 요청은 어느 layer 문제인지 확인하고 패치한다. 추측으로 정규화 로직을 손대지 않는다.
- **배치 작업 추가/수정 시:** `BatchScheduler`에 등록된 26개 잡 중 어떤 잡과 의존성이 있는지(예: `RankingUpdateBatch` → `RankingSnapshotBatch`, `BadgeAwardBatch` ← `DailyStatsBatch`) 확인 후 진행.
- **Multi-tier(LP) 로직:** 승급/강등 경계 처리는 `MultiTierService` + `LpDecayBatch` + 매치 종료 시 LP 변동 로직이 동시에 영향. 한 군데만 고치지 않는다.
- **"화면이 깨졌어요" 요청:** 어떤 모드(Solo Guess / Host / Fan Challenge / Retro / Multi)인지, 어떤 테마(라이트/다크/`.game-page`)인지, 어떤 브레이크포인트(PC/768px/480px)인지 먼저 확인한다.

### 2. Simplicity First (단순함이 먼저)

**문제를 해결하는 최소 코드. 투기적인 것은 없다.**

- 요청되지 않은 기능은 추가하지 않는다.
- 1회용 코드에 추상화 계층을 만들지 않는다.
- 요청되지 않은 "유연성"이나 "설정 가능성"을 끼워넣지 않는다.
- 발생할 수 없는 시나리오에 예외 처리를 하지 않는다.
- 200줄로 쓴 것이 50줄로 가능했다면, 다시 쓴다.

자문: **"시니어 엔지니어가 이건 오버엔지니어링이라고 할까?"** 그렇다면 단순화한다.

**이 프로젝트에서의 안티 패턴:**

- 한 Service에서만 쓰는 로직을 `XxxStrategy` 인터페이스 + 구현체로 분리하지 말 것.
- Batch Job 추가하는데 새로운 추상 `AbstractScheduledJob` 만들지 말 것. `BatchScheduler` + `BatchConfig`(cron 외부화) 기존 패턴을 따른다.
- 게임 모드 추가 요청 시 "확장성"을 이유로 게임 엔진을 일반화하지 말 것. 기존 `GameSessionService` / `MultiGameService` / `FanChallengeService` 패턴을 그대로 답습한다.
- 관리자 페이지 한 곳에서만 쓰는 DTO를 굳이 entity와 양방향 매핑하지 말 것. 명확히 분리한다.
- 한 fragment에서만 쓰는 Thymeleaf 헬퍼를 굳이 `#strings` 확장으로 만들지 말 것. 인라인이면 인라인이다.

### 3. Surgical Changes (외과적 변경)

**필요한 곳만 건드린다. 내가 만든 흔적만 정리한다.**

기존 코드를 수정할 때:

- 인접한 코드, 주석, 포매팅을 "개선"하지 않는다.
- 망가지지 않은 것을 리팩토링하지 않는다.
- 내가 다르게 작성할 스타일이라도 **기존 스타일에 맞춘다.**
- 무관한 dead code를 발견하면 *언급만* 한다. 삭제하지 않는다.

내 변경이 고아(orphan)를 만들었다면:

- *내 변경*으로 인해 사용되지 않게 된 import/변수/함수만 제거한다.
- 변경 전부터 있던 dead code는 요청 없이 제거하지 않는다.

검증 기준: **변경된 모든 라인은 사용자 요청과 직접 연결되어야 한다.**

**이 프로젝트에서의 특별 주의:**

- **`common.css`의 CSS 변수 시스템(`:root`, `[data-theme="dark"]`, `.game-page`)은 의도된 디자인이다.** 무관한 작업 중에 변수 정의를 "정리"하지 않는다.
- **`SecurityConfig` / `WebConfig`의 interceptor 등록 순서**는 의존성이 있다(`AdminInterceptor`, `SessionValidationInterceptor`). 새 인터셉터 추가하면서 기존 등록 순서를 재배열하지 않는다.
- **Thymeleaf fragment(`fragments/header.html`, `fragments/footer.html` 등)는 모든 화면이 의존한다.** "더 깔끔하게" 변경하지 않는다.
- **`docker-compose.yml`은 서버와 자동 동기화되지 않는다**(아래 경고 섹션 참고). 무관한 작업 중에 compose 파일을 건드리지 않는다.
- **CI/CD 워크플로(`.github/workflows/deploy.yml`)는 한 번 맞춰놨다.** 다른 작업 중에 build step 순서나 path filter를 "최적화"하지 않는다.
- **`BatchConfig` 테이블의 cron 식**은 운영 중 DB에서 조정한다. 코드에서 cron 기본값을 무관한 작업 중에 변경하지 않는다.

### 4. Goal-Driven Execution (목표 주도 실행)

**성공 기준을 정의한다. 검증될 때까지 루프를 돈다.**

| 명령형 지시 | 목표형 변환 |
|------------|------------|
| "validation 추가해줘" | "잘못된 입력 테스트를 쓰고, 통과시켜라" |
| "버그 고쳐줘" | "버그를 재현하는 테스트/시나리오를 만들고, 통과시켜라" |
| "X를 리팩토링해줘" | "리팩토링 전후로 테스트가 모두 통과하는지 확인" |

다단계 작업은 짧은 계획을 먼저 제시:

```
1. [단계] → 검증: [확인 방법]
2. [단계] → 검증: [확인 방법]
3. [단계] → 검증: [확인 방법]
```

**이 프로젝트에서의 검증 패턴:**

- **Controller 추가:** Controller 작성 → `./mvnw test` 통과 → 가능하면 `MockMvc` 테스트로 200 / 4xx / 5xx 응답 검증.
- **JPA Repository 메서드 추가:** 메서드 작성 → dev 프로파일 실행 → 실제 데이터로 결과 row 수/내용 확인.
- **Batch Job 작업:** Job 추가/수정 → `AdminBatchController`에서 수동 trigger 또는 cron 조정 → `BatchExecutionHistory`에서 `COMPLETED` 확인 → 결과 테이블 검증.
- **JPA 쿼리 최적화("느려요"):** `EXPLAIN` 결과를 **먼저** 확인하고, 인덱스/쿼리 변경 후 다시 `EXPLAIN`으로 검증.
- **Thymeleaf 화면 변경:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (포트 8082) → 라이트/다크 + PC/태블릿(768px)/모바일(480px) 모두 시각 확인.
- **CSS 변경:** 라이트 + 다크 + `.game-page` 3개 테마 + PC/768px/480px 3개 브레이크포인트 = **9개 조합**을 의식해서 작성, 가능한 한 모두 확인.
- **Docker 배포 작업:** 서버에서 `docker compose up -d --no-deps --force-recreate app` 실행 후 `docker compose logs -f app`으로 부팅 로그 확인 → 영향 받는 엔드포인트에 curl로 응답 검증.

---

## 프로젝트 고유 규칙 (행동)

- **구현 가이드 / 통합 가이드 / 작업 보고 문서는 사용자가 명시적으로 요청하기 전까지 만들지 않는다.** 코드부터 끝낸다.
- **시크릿/키 파일은 `application-secret.properties` 또는 `.env` 환경변수로 분리하고 절대 커밋하지 않는다** (`BREVO_API_KEY`, `DB_PASSWORD`, `DOCKERHUB_TOKEN` 등).
- **모든 환경 의존 값(경로, 호스트, 포트, 키)은 `application.properties` 또는 환경변수로 외부화한다.** 코드 내 하드코딩 금지.
- 한국어 답변 OK. **단, 변수명 / 함수명 / 커밋 메시지는 영어 또는 코드 컨벤션에 맞춘 한국어**로 통일하고, 절대 혼용하지 않는다.

**이 가이드라인이 잘 작동하고 있다는 신호:**

- diff에 불필요한 변경이 없다 — 요청한 변경만 나타난다.
- 오버엔지니어링으로 인한 재작성이 줄어든다 — 처음부터 단순하다.
- 실수 *후*가 아니라 구현 *전*에 명확화 질문이 온다.
- 깔끔하고 최소한의 PR — 지나가다 하는 "개선"이 없다.

---

## ⚠️ 필수 준수 사항 (Quick Reference)

**모든 작업 완료 전 반드시 확인할 것!**

### CSS 작업 시
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| 색상 | `#1e293b`, `rgba(0,0,0,0.5)` | `var(--text-primary)`, `var(--overlay-medium)` |
| 테마 | 라이트 모드만 작성 | 라이트 + 다크 모드 + `.game-page` 모두 정의 |
| 반응형 | PC만 작성, 임의 브레이크포인트 | PC + 태블릿(768px) + 모바일(480px) 3단계 |
| 단위 | `width: 350px` | `width: 100%`, `max-width: 24rem` |
| z-index | `9999`, `99999` | 정해진 계층값 사용 (모달=1000, 토스트=5000) |

### 보안 (모든 기능 구현 시)
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| SQL | `"SELECT * FROM song WHERE title = '" + title + "'"` | `@Param` 바인딩, JPA 메서드 쿼리 |
| XSS | `th:utext="${userInput}"`, `innerHTML = userInput` | `th:text`, `textContent` |
| 인증 | API에 세션 검증 없음 | 모든 POST/PUT/DELETE에 인증+권한 검증 |
| IDOR | ID만으로 리소스 접근 | 소유권 검증 (`member.getId().equals(...)`) |

### Thymeleaf 작업 시
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| 이벤트 핸들러 | `th:onclick="'fn(\'' + ${var} + '\')'"` | `th:data-x="${var}"` + `onclick="fn(this.dataset.x)"` |
| 문자열 출력 | `th:utext="${userInput}"` | `th:text="${userInput}"` |
| URL 파라미터 | `th:href="'/path?id=' + ${id}"` | `th:href="@{/path(id=${id})}"` |

> **이유**: Thymeleaf 보안 정책상 `onclick`, `onload` 등 이벤트 핸들러에서 문자열 변수 직접 삽입 금지 (XSS 방지)

### 빌드
- `mvn` ❌ → `./mvnw` ✅
- Java 17 필수 (`JAVA_HOME` 설정)

### Git 커밋
- 커밋 메시지에 `Co-Authored-By: Claude` 등 AI 관련 트레일러 포함 금지

---

## Build & Run Commands

**IMPORTANT:**
- Use `./mvnw` (Maven Wrapper) instead of `mvn` for all commands
- Set JAVA_HOME to Java 17 before running commands

```bash
# Set Java 17 (required for all commands) - adjust path to your JDK location
export JAVA_HOME="/c/Users/rbgud/.jdks/corretto-17.0.12"  # Windows/Git Bash (집 PC)
# export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.12-amzn"  # Linux/macOS with SDKMAN

# Run application (dev profile, port 8082)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Build WAR package
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=GameApplicationTests

# Run a single test method
./mvnw test -Dtest=GameApplicationTests#testMethodName

# Docker deployment (production)
docker-compose up -d

# View logs
docker-compose logs -f app
```

## Architecture Overview

This is a **multiplayer music guessing game** built with Spring Boot 3.4.1 + Java 17 + MariaDB.

### Layered Architecture

```
Controller (MVC + REST) → Service (Business Logic) → Repository (JPA) → MariaDB
         ↓
    Thymeleaf Templates (server-side rendering)
```

### Package Structure (`com.kh.game`)

- **controller/client/** - User-facing: `HomeController`, `AuthController`, `GameGuessController`, `GameHostController`, `GameFanChallengeController`, `RetroGameController`, `MultiGameController`, `RankingController`, `SongReportController`, `BoardController`, `StatsController`, `MyPageController`
- **controller/admin/** - Admin panel: `AdminController` (dashboard), `AdminSongController`, `AdminGenreController`, `AdminBatchController`, `AdminBadWordController`, `AdminRoomController`, `AdminChatController`, `AdminSongReportController`, `AdminMemberController`, `AdminGameHistoryController`, `AdminStatsController`, `AdminAnswerController`, `AdminLoginHistoryController`, `AdminFanChallengeController`, `AdminMenuController`
- **service/** - Business logic: `GameSessionService`, `MultiGameService`, `SongService`, `MemberService`, `GameRoomService`, `AnswerValidationService`, `YouTubeValidationService`, `BoardService`, `WrongAnswerStatsService`, `BatchService`, `GenreMigrationService`, `GenreService`, `MultiTierService`, `FanChallengeService`, `BadgeService`, `MenuConfigService`, `EmailVerificationService`
- **entity/** - JPA entities: `Member`, `MemberLoginHistory`, `Song`, `SongAnswer`, `Genre`, `GameSession`, `GameRound`, `GameRoundAttempt`, `GameRoom`, `GameRoomParticipant`, `GameRoomChat`, `BadWord`, `SongReport`, `BatchConfig`, `BatchExecutionHistory`, `DailyStats`, `Board`, `BoardComment`, `BoardLike`, `Badge`, `MemberBadge`, `MultiTier`, `FanChallengeDifficulty`, `FanChallengeRecord`, `RankingHistory`, `MenuConfig`, `EmailVerification`
- **repository/** - Spring Data JPA repositories
- **batch/** - 26 scheduled batch jobs managed by `BatchScheduler`
- **config/** - `SecurityConfig` (BCrypt), `WebConfig` (interceptors, file upload), `SchedulerConfig`, `DataInitializer`
- **security/** - `LoginRateLimiter` (bucket4j 토큰 버킷, IP별 분당 20회 제한, 화이트리스트 지원)
- **util/** - `AnswerGeneratorUtil` (English→Korean phonetic conversion for song titles), `SecurityInputValidator` (이메일 정규식 + SQL Injection 패턴 차단)
- **dto/** - `GameSettings` (multiplayer room configuration)
- **interceptor/** - `AdminInterceptor`, `SessionValidationInterceptor`

### Game Modes

1. **Solo Guess** - User guesses songs with 3 attempts. Supports various modes (RANDOM, FIXED_GENRE, FIXED_ARTIST, FIXED_YEAR, per-round selection). Includes "30-song Challenge" for ranked play.
2. **Solo Host** - User reads clues for others to guess (100/70/50 points)
3. **Fan Challenge** - Artist-focused 30-song challenge with two difficulty levels (NORMAL/HARDCORE). Time-based play (NORMAL 7s listen + 6s answer, HARDCORE 5s + 5s; HARDCORE only is ranked), perfect game tracking, artist-specific rankings.
4. **Retro Game** - Nostalgia mode featuring songs from before 2000s.
5. **Multiplayer** - Room-based game with real-time chat polling, first correct answer scores 100 points. LP-based tier system (Bronze→Challenger).

### Key Data Flow

- `GameSession` → contains `GameRound` → tracks `GameRoundAttempt` (solo mode)
- `GameRoom` → has `GameRoomParticipant` → stores `GameRoomChat` (multiplayer mode)
- `Song` → has multiple `SongAnswer` for fuzzy matching validation
- `Board` → has `BoardComment` and `BoardLike` (community board)
- `Member` → has `MemberBadge` → links to `Badge` (achievement system)
- `FanChallengeRecord` → tracks artist challenge attempts with difficulty and score
- `RankingHistory` → stores daily ranking snapshots for historical tracking
- `MenuConfig` → configurable navigation menu items for client UI

### Multiplayer Flow

1. Create room → join room → toggle ready
2. Host starts game → `PREPARING` phase (all participants load song)
3. Each participant calls `/round-ready` when ready
4. Host starts round → `PLAYING` phase (song plays, chat for answers)
5. First correct answer wins → show answer → next round or game end

### Key Services

- **AnswerValidationService** - Validates user answers with normalization (lowercase, strip spaces/special chars, keep only alphanumeric + Korean), checks both `Song.title` and `SongAnswer` table
- **AnswerGeneratorUtil** - Generates answer variants including English→Korean phonetic conversion using word/phoneme mapping tables (~700 common words)
- **YouTubeValidationService** - Two-phase validation: oEmbed API check → thumbnail size check (detects deleted videos)
- **BadWordService** - Profanity filtering with ConcurrentHashMap cache, auto-reloads on changes
- **SongReportService** - Handles user reports for problematic songs
- **DataInitializer** - Seeds initial bad words (~50 profanities) on startup via CommandLineRunner
- **BoardService** - Community board CRUD with category filtering, comments, and likes
- **MultiTierService** - LP and tier management for multiplayer with ELO-based rating calculations
- **FanChallengeService** - Artist challenge game logic with difficulty-based scoring
- **BadgeService** - Achievement badge management with automatic and manual award conditions
- **EmailVerificationService** - 회원가입 시 6자리 이메일 인증 코드 발급/검증. **Brevo Transactional Email API**(HTTPS) 사용 — cafe24가 SMTP 포트(25/465/587) outbound를 차단해 Gmail SMTP 대신 도입. `RestClient`로 호출하며 4xx/5xx/네트워크 에러를 분리 처리
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

All batches are DB-configurable via `BatchConfig` table with cron expressions:
- **Cleanup:** `SessionCleanupBatch`, `GameSessionCleanupBatch`, `RoomCleanupBatch`, `ChatCleanupBatch`, `BoardCleanupBatch`, `LoginHistoryCleanupBatch`, `BatchExecutionHistoryCleanupBatch`, `GameRoundAttemptCleanupBatch`, `SongReportCleanupBatch`
- **Stats & Rankings:** `DailyStatsBatch`, `RankingUpdateBatch`, `RankingSnapshotBatch`, `WeeklyRankingResetBatch`, `MonthlyRankingResetBatch`
- **Member Management:** `InactiveMemberBatch`, `BadgeAwardBatch`, `LpDecayBatch`, `LoginStreakBatch`
- **Song Integrity:** `SongFileCheckBatch`, `SongAnalyticsBatch`, `YouTubeVideoCheckBatch`, `DuplicateSongCheckBatch`, `SongAnswerGenerationBatch`
- **Fan Challenge:** `FanChallengePerfectCheckBatch`, `WeeklyPerfectRefreshBatch`
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

> 값 출처: `FanChallengeDifficulty` enum (NORMAL 7000/6000, HARDCORE 5000/5000, 생명 3, `isShowChosungHint`=false, `isRanked`=HARDCORE만). 난이도는 2단계뿐.

### Community Board

- Categories: REQUEST (곡 추천/요청), OPINION (의견/후기), QUESTION (질문), FREE (자유)
- Features: Comments, likes, view count tracking
- Status: ACTIVE, DELETED, HIDDEN

## Configuration

- **Dev profile:** Port 8082, MariaDB localhost:3306/song (root/1234), JPA ddl-auto=update
- **Prod profile:** Uses environment variables for DB credentials, Docker volumes for persistence
- **Admin auth:** DB-based via `Member` table with `role=ADMIN`
- **File uploads:** `uploads/songs/`, max 50MB
- **Session timeout:** 30 minutes
- **Admin routes:** Protected by `AdminInterceptor` (/admin/**)
- **Docker memory:** App 512MB, DB 256MB

### 환경변수 (Production `.env` 필수 항목)

| 변수 | 용도 | 예시 |
|------|------|------|
| `DOCKERHUB_USERNAME` | Docker Hub 사용자명 (이미지 pull) | `positivelee` |
| `DB_USERNAME` / `DB_PASSWORD` | MariaDB 자격 증명 | `root` / `...` |
| `BREVO_API_KEY` | Brevo Transactional Email API 키 (`xkeysib-...`) | brevo.com에서 발급 |
| `MAIL_FROM` | 인증 메일 발신자 주소 (Brevo에서 사전 검증 필수) | `noreply@example.com` |

> ⚠️ **Brevo 보안 설정**: Brevo 대시보드의 `Security → Authorised IPs`에 운영 서버 IP를 등록해야 함. 미등록 시 모든 API 호출이 `401 unauthorized`로 거부됨. 서버 IP가 바뀌면 재등록 필요.

### `docker-compose.yml` 동기화 주의

서버 `/root/game/docker-compose.yml`은 git 저장소의 파일과 **자동 동기화되지 않음**. compose 파일이 변경되면(예: 환경변수 추가/이름 변경) 서버에 직접 반영 후 `docker compose up -d --no-deps --force-recreate app` 실행 필요. 이미지만 갱신해도 새 환경변수는 적용 안 됨.

## CI/CD

GitHub Actions workflow at `.github/workflows/deploy.yml`:
- Triggers on push to main or manual dispatch (ignores *.md, .claude/**, .gitignore, LICENSE)
- Builds Docker image → pushes to Docker Hub → deploys to server via SSH
- Requires secrets: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_PORT`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`

## Security Review Guide

**⚠️ CRITICAL: 모든 기능 구현 시 보안 검토 필수!**

### 기능 구현 단계별 보안 체크

모든 기능 구현은 다음 5단계를 거쳐야 함:

```
1. 설계 → 2. 구현 → 3. 보안 검토 → 4. 테스트 → 5. 코드 리뷰
                        ↑
                   [필수 단계]
```

### 1. SQL Injection 방지

**⚠️ 절대 금지: 문자열 연결로 쿼리 생성**

```java
// ❌ 취약한 코드 - 절대 금지
@Query("SELECT s FROM Song s WHERE s.title = '" + title + "'")
List<Song> findByTitle(String title);

String sql = "SELECT * FROM song WHERE artist = '" + artist + "'";
jdbcTemplate.query(sql, ...);

// ✅ 안전한 코드 - Spring Data JPA 메서드 쿼리
List<Song> findByTitle(String title);
List<Song> findByArtistContaining(String artist);

// ✅ 안전한 코드 - 파라미터 바인딩 사용
@Query("SELECT s FROM Song s WHERE s.title = :title")
List<Song> findByTitle(@Param("title") String title);

// ✅ 안전한 코드 - Native Query도 파라미터 바인딩
@Query(value = "SELECT * FROM song WHERE genre_id = :genreId", nativeQuery = true)
List<Song> findByGenre(@Param("genreId") Long genreId);
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `@Query`에 문자열 연결(`+`) 없음 | ☐ |
| Native Query 사용 시 `:param` 바인딩 사용 | ☐ |
| `JdbcTemplate` 사용 시 `?` 플레이스홀더 사용 | ☐ |
| 동적 쿼리는 `Specification` 또는 `QueryDSL` 사용 | ☐ |
| 검색/필터 기능에 사용자 입력 직접 삽입 없음 | ☐ |

---

### 2. XSS (Cross-Site Scripting) 방지

**⚠️ Thymeleaf 기본 이스케이프 활용 + 위험 패턴 금지**

```html
<!-- ❌ 취약한 코드 - 절대 금지 -->
<span th:utext="${userInput}"></span>  <!-- utext는 HTML 그대로 출력 -->
<div th:attr="onclick='alert(' + ${userInput} + ')'"></div>
<script th:inline="javascript">
    var data = /*[[${unsafeData}]]*/ '';  // 문자열 외부 삽입
</script>

<!-- ✅ 안전한 코드 - 기본 이스케이프 사용 -->
<span th:text="${userInput}"></span>  <!-- HTML 이스케이프됨 -->
<span th:text="${board.content}"></span>

<!-- ✅ 안전한 코드 - JavaScript 내 데이터 전달 -->
<script th:inline="javascript">
    var data = /*[[${safeData}]]*/ '';  // 문자열 리터럴 내부는 안전
    var config = /*[[${jsonData}]]*/ {};  // JSON도 이스케이프됨
</script>

<!-- ✅ 안전한 코드 - data 속성 활용 -->
<div th:data-song-id="${song.id}" th:data-title="${song.title}"></div>
<script>
    const songId = element.dataset.songId;  // 안전하게 접근
</script>
```

**JavaScript에서 DOM 조작 시:**

```javascript
// ❌ 취약한 코드
element.innerHTML = userInput;
document.write(userInput);
$('#target').html(userInput);

// ✅ 안전한 코드
element.textContent = userInput;  // HTML 태그 무효화
$('#target').text(userInput);     // jQuery에서 안전한 방법

// ✅ HTML이 필요한 경우 - 서버에서 화이트리스트 필터링 후 전달
element.innerHTML = sanitizedHtmlFromServer;
```

**서버 측 입력 검증 (Service Layer):**

```java
// 게시판, 채팅 등 사용자 입력 처리 시
public String sanitizeInput(String input) {
    if (input == null) return null;
    // HTML 태그 제거 (필요시 jsoup 라이브러리 활용)
    return Jsoup.clean(input, Whitelist.none());
}

// 게시판 저장 전 검증
public Board saveBoard(BoardDto dto) {
    dto.setTitle(sanitizeInput(dto.getTitle()));
    dto.setContent(sanitizeInput(dto.getContent()));  // HTML 허용 시 Whitelist.basic() 사용
    // ...
}
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `th:utext` 사용 시 입력값이 아닌 안전한 데이터만 사용 | ☐ |
| JavaScript `innerHTML` 대신 `textContent` 사용 | ☐ |
| 사용자 입력을 URL 파라미터로 반영 시 인코딩 적용 | ☐ |
| 게시판/채팅 입력값 서버 측 필터링 적용 | ☐ |
| JSON API 응답에 Content-Type: application/json 설정 | ☐ |

---

### 3. 인증/인가 우회 방지

**⚠️ 모든 보호 리소스에 인증/인가 검증 필수**

**Controller 레벨 검증:**

```java
// ❌ 취약한 코드 - 인증 검증 없음
@GetMapping("/admin/members")
public String listMembers(Model model) {
    model.addAttribute("members", memberService.findAll());
    return "admin/members";
}

// ❌ 취약한 코드 - 세션만 확인, 권한 미확인
@PostMapping("/admin/song/delete/{id}")
public String deleteSong(@PathVariable Long id, HttpSession session) {
    if (session.getAttribute("member") != null) {  // 로그인만 확인
        songService.delete(id);
    }
    return "redirect:/admin/songs";
}

// ✅ 안전한 코드 - 권한까지 검증
@PostMapping("/admin/song/delete/{id}")
public String deleteSong(@PathVariable Long id, HttpSession session) {
    Member member = (Member) session.getAttribute("member");
    if (member == null || !"ADMIN".equals(member.getRole())) {
        throw new AccessDeniedException("관리자 권한이 필요합니다.");
    }
    songService.delete(id);
    return "redirect:/admin/songs";
}

// ✅ 더 안전한 코드 - Interceptor에서 일괄 처리 (현재 AdminInterceptor 활용)
// SecurityConfig 또는 WebConfig에서 /admin/** 경로 보호 설정 확인
```

**리소스 소유권 검증 (IDOR 방지):**

```java
// ❌ 취약한 코드 - 다른 사용자 데이터 접근 가능
@GetMapping("/mypage/history/{sessionId}")
public String viewHistory(@PathVariable Long sessionId, Model model) {
    GameSession session = gameSessionService.findById(sessionId);
    model.addAttribute("session", session);  // 누구의 세션이든 조회 가능!
    return "mypage/history";
}

// ✅ 안전한 코드 - 소유권 검증
@GetMapping("/mypage/history/{sessionId}")
public String viewHistory(@PathVariable Long sessionId, HttpSession httpSession, Model model) {
    Member member = (Member) httpSession.getAttribute("member");
    GameSession session = gameSessionService.findById(sessionId);

    // 본인 소유 데이터인지 확인
    if (!session.getMember().getId().equals(member.getId())) {
        throw new AccessDeniedException("본인의 게임 기록만 조회할 수 있습니다.");
    }

    model.addAttribute("session", session);
    return "mypage/history";
}

// ✅ Repository 레벨에서 검증하는 방법
@Query("SELECT g FROM GameSession g WHERE g.id = :sessionId AND g.member.id = :memberId")
Optional<GameSession> findByIdAndMemberId(@Param("sessionId") Long sessionId,
                                          @Param("memberId") Long memberId);
```

**API 엔드포인트 보호:**

```java
// ❌ 취약한 코드 - AJAX 요청에 인증 없음
@PostMapping("/api/room/{roomId}/kick/{memberId}")
@ResponseBody
public ResponseEntity<?> kickMember(@PathVariable Long roomId, @PathVariable Long memberId) {
    gameRoomService.kickMember(roomId, memberId);
    return ResponseEntity.ok().build();
}

// ✅ 안전한 코드 - 방장 권한 확인
@PostMapping("/api/room/{roomId}/kick/{memberId}")
@ResponseBody
public ResponseEntity<?> kickMember(@PathVariable Long roomId,
                                    @PathVariable Long memberId,
                                    HttpSession session) {
    Member currentMember = (Member) session.getAttribute("member");
    if (currentMember == null) {
        return ResponseEntity.status(401).body("로그인이 필요합니다.");
    }

    GameRoom room = gameRoomService.findById(roomId);
    if (!room.getHost().getId().equals(currentMember.getId())) {
        return ResponseEntity.status(403).body("방장만 강퇴할 수 있습니다.");
    }

    gameRoomService.kickMember(roomId, memberId);
    return ResponseEntity.ok().build();
}
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `/admin/**` 경로에 AdminInterceptor 적용 확인 | ☐ |
| 모든 POST/PUT/DELETE에 인증 검증 존재 | ☐ |
| 리소스 접근 시 소유권(IDOR) 검증 존재 | ☐ |
| API 엔드포인트에도 세션 검증 적용 | ☐ |
| 권한 상승 가능한 기능에 role 검증 존재 | ☐ |
| 비밀번호 변경 시 현재 비밀번호 확인 | ☐ |

---

### 4. 추가 보안 검토 항목

#### CSRF (Cross-Site Request Forgery)

```html
<!-- Thymeleaf 폼에서 자동 CSRF 토큰 삽입 (Spring Security 사용 시) -->
<form th:action="@{/board/save}" method="post">
    <!-- th:action 사용 시 자동으로 _csrf 토큰 추가됨 -->
    <input type="text" name="title" />
    <button type="submit">저장</button>
</form>

<!-- AJAX 요청 시 CSRF 토큰 전달 -->
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>

<script>
const csrfToken = document.querySelector('meta[name="_csrf"]').content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

fetch('/api/board/save', {
    method: 'POST',
    headers: {
        [csrfHeader]: csrfToken,
        'Content-Type': 'application/json'
    },
    body: JSON.stringify(data)
});
</script>
```

#### 파일 업로드 보안

```java
// ✅ 안전한 파일 업로드 처리
public String saveUploadFile(MultipartFile file) {
    // 1. 파일 확장자 화이트리스트 검증
    String originalFilename = file.getOriginalFilename();
    String extension = getExtension(originalFilename).toLowerCase();
    List<String> allowedExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "mp3");

    if (!allowedExtensions.contains(extension)) {
        throw new IllegalArgumentException("허용되지 않는 파일 형식입니다.");
    }

    // 2. 파일 크기 검증 (application.yml에서도 설정)
    if (file.getSize() > 50 * 1024 * 1024) {  // 50MB
        throw new IllegalArgumentException("파일 크기가 너무 큽니다.");
    }

    // 3. 저장 파일명은 UUID로 변경 (경로 조작 방지)
    String savedFilename = UUID.randomUUID().toString() + "." + extension;

    // 4. 저장 경로는 웹 루트 외부로 설정
    Path uploadPath = Paths.get(uploadDir).resolve(savedFilename);
    Files.copy(file.getInputStream(), uploadPath);

    return savedFilename;
}
```

#### 민감 정보 로깅 금지

```java
// ❌ 금지 - 비밀번호, 개인정보 로깅
log.info("Login attempt: username={}, password={}", username, password);
log.debug("Member info: {}", member);  // toString()에 민감정보 포함 시

// ✅ 안전한 로깅
log.info("Login attempt: username={}", username);
log.info("Login success: memberId={}", member.getId());
```

---

### 보안 검토 최종 체크리스트

기능 구현 완료 후 아래 항목을 모두 확인:

| 카테고리 | 항목 | 확인 |
|----------|------|------|
| **SQL Injection** | 모든 쿼리에 파라미터 바인딩 사용 | ☐ |
| | 동적 검색 조건에 문자열 연결 없음 | ☐ |
| **XSS** | 사용자 입력 출력 시 `th:text` 사용 | ☐ |
| | JavaScript에서 `textContent` 사용 | ☐ |
| | 서버 측 입력값 필터링 적용 | ☐ |
| **인증** | 보호 리소스에 로그인 검증 존재 | ☐ |
| | 관리자 기능에 권한 검증 존재 | ☐ |
| **인가** | 리소스 소유권(IDOR) 검증 존재 | ☐ |
| | API 엔드포인트 권한 검증 존재 | ☐ |
| **CSRF** | 상태 변경 요청에 CSRF 토큰 적용 | ☐ |
| **파일** | 업로드 파일 확장자 화이트리스트 검증 | ☐ |
| | 저장 파일명 UUID로 변경 | ☐ |
| **로깅** | 민감정보(비밀번호 등) 로깅 없음 | ☐ |
| **세션** | 로그인 성공 시 세션 ID 재생성 | ☐ |

## CSS Style Guide

**⚠️ CRITICAL: 색상 하드코딩 금지!**

### 필수 규칙
- **색상값을 직접 쓰지 말고 반드시 CSS 변수 사용** (`#1e293b` ❌ → `var(--text-primary)` ✅)
- 새로운 색상이 필요하면 `common.css`의 `:root`에 변수 추가 후 사용
- **⚠️ 라이트/다크 모드 1:1 매칭 필수** - 모든 스타일은 라이트와 다크 모드 양쪽에 정의해야 함
- **⚠️ 반응형 3단계 필수** - 모든 레이아웃/크기 관련 스타일은 PC/태블릿/모바일 3단계로 정의해야 함

### 테마 시스템 구조
```
common.css
├── :root { }                    → 라이트 모드 기본값
├── [data-theme="dark"] { }      → 다크 모드 오버라이드
└── .game-page { }               → 게임 페이지 전용 (항상 다크)
```

### 주요 CSS 변수
| 용도 | 변수명 |
|------|--------|
| 기본 텍스트 | `--text-primary` |
| 보조 텍스트 | `--text-secondary` |
| 흐린 텍스트 | `--text-muted` |
| 기본 배경 | `--bg-base` |
| 카드 배경 | `--bg-surface` |
| 강조 배경 | `--bg-elevated` |
| 테두리 | `--border-color` |

### 주의사항: `.game-page` 클래스
- `.game-page`는 CSS 변수를 다크 모드로 강제 오버라이드함
- **흰색 배경 요소**에서 `var(--text-primary)`를 쓰면 흰 글씨가 됨!
- 해결법: 해당 CSS 파일의 다크 테마 섹션(`[data-theme="dark"]`)에서 별도 처리

### 예시: 흰색 배경 모달 처리
```css
/* 기본 스타일 */
.modal-content {
    background: white;
    color: var(--text-primary);  /* 라이트 모드에서 정상 작동 */
}

/* 다크 모드 또는 .game-page에서 오버라이드 */
[data-theme="dark"] .modal-content,
.game-page .modal-content {
    background: var(--bg-surface);  /* 다크 배경으로 변경 */
    color: var(--text-primary);     /* 이제 흰 글씨가 맞음 */
}
```

### ⚠️ CSS 작성 필수 템플릿

새로운 컴포넌트 CSS 작성 시 반드시 아래 구조를 따라야 함:

```css
/* ========================================
   [컴포넌트명] - 기본 스타일 (라이트 모드 + PC)
   ======================================== */
.component {
    background: var(--bg-surface);
    color: var(--text-primary);
    padding: 2rem;
    font-size: 1rem;
}

/* ========================================
   [컴포넌트명] - 다크 모드
   ======================================== */
[data-theme="dark"] .component {
    background: var(--bg-elevated);
    border-color: var(--border-color);
}

/* .game-page는 항상 다크 모드이므로 함께 처리 */
.game-page .component {
    background: var(--bg-elevated);
    border-color: var(--border-color);
}

/* ========================================
   [컴포넌트명] - 태블릿 (768px 이하)
   ======================================== */
@media (max-width: 768px) {
    .component {
        padding: 1.5rem;
        font-size: 0.95rem;
    }
}

/* ========================================
   [컴포넌트명] - 모바일 (480px 이하)
   ======================================== */
@media (max-width: 480px) {
    .component {
        padding: 1rem;
        font-size: 0.9rem;
    }
}
```

### 체크리스트: CSS 작성 완료 전 확인

| 항목 | 확인 |
|------|------|
| `:root` (라이트 모드) 스타일 정의 | ☐ |
| `[data-theme="dark"]` 스타일 정의 | ☐ |
| `.game-page` 스타일 정의 (필요시) | ☐ |
| `@media (max-width: 768px)` 태블릿 스타일 | ☐ |
| `@media (max-width: 480px)` 모바일 스타일 | ☐ |
| 색상값 하드코딩 없음 | ☐ |
| CSS 변수만 사용 | ☐ |

### 반응형 브레이크포인트

| 구분 | 브레이크포인트 | 용도 |
|------|---------------|------|
| **모바일** | `max-width: 480px` | 스마트폰 세로 |
| **태블릿** | `max-width: 768px` | 태블릿/스마트폰 가로 |
| **데스크탑** | `min-width: 769px` | PC (기본) |
| **대형** | `min-width: 1200px` | 대형 모니터 (선택적) |

```css
/* 기본: 데스크탑 스타일 */
.container { padding: 2rem; }

/* 태블릿 이하 */
@media (max-width: 768px) {
    .container { padding: 1.5rem; }
}

/* 모바일 */
@media (max-width: 480px) {
    .container { padding: 1rem; }
}
```

**⚠️ 금지:** 임의의 브레이크포인트 사용 (450px, 375px 등)

**예외:** `game-multi.css`는 채팅+스코어보드 레이아웃 특성상 `900px` 브레이크포인트 허용

### Z-Index 계층

| 계층 | 값 | 용도 |
|------|-----|------|
| 기본 | `1-10` | 로컬 스태킹 (카드 내 요소) |
| 고정 | `100` | 사이드바, 네비게이션 |
| 드롭다운 | `500` | 드롭다운, 팝오버 |
| 모달 배경 | `900` | 모달 오버레이 |
| 모달 | `1000` | 모달 콘텐츠 |
| 토스트 | `5000` | 알림 토스트 |
| 최상위 | `10000` | 뱃지 토스트 (특수) |

**⚠️ 금지:** 임의의 z-index 값 사용 (9999, 99999 등)

### 단위 & 값 규칙

- **Width/Height:** 반응형 단위 우선 사용
  - 우선순위: `%` → `vw/vh` → `rem` → `px` (최후의 수단)
  - 컨테이너 기준: `%` 사용 (예: `width: 100%`)
  - 뷰포트 기준: `vw/vh` 사용 (예: `max-height: 80vh`)
  - 고정 크기 필요 시: `rem` 사용 (예: `min-width: 20rem`)
- **길이:** `rem` 사용 (px 금지, 예외: `1px` 보더)
- **Border-radius:** `rem` 단위만 사용
  - 작음: `0.25rem` / 중간: `0.5rem` / 큼: `0.75rem` / 매우 큼: `1rem` / 원형: `50%`
- **Spacing:** `0.25rem` 단위로 증가 (0.5rem, 0.75rem, 1rem, 1.5rem, 2rem)
- **Transition:** `0.2s` (빠름) / `0.3s` (기본) / `0.5s` (느림)

```css
/* ❌ 잘못된 예 */
width: 350px;            /* 고정 px */
height: 500px;           /* 고정 px */
border-radius: 6px;      /* px 사용 */
padding: 13px;           /* px 사용 */

/* ✅ 올바른 예 */
width: 100%;             /* 부모 기준 반응형 */
max-width: 24rem;        /* 최대값 제한 */
height: 80vh;            /* 뷰포트 기준 */
border-radius: 0.5rem;   /* 표준 값 */
padding: 0.75rem;        /* rem 사용 */
```

### RGBA 투명도 처리

투명도가 필요한 색상도 변수 사용 권장. `common.css`에 정의된 `--overlay-*` 변수 활용:

```css
/* ❌ 하드코딩 */
background: rgba(0, 0, 0, 0.5);
color: rgba(255, 255, 255, 0.7);

/* ✅ 변수 사용 */
background: var(--overlay-medium);
color: var(--text-secondary);
```

## 서버 인프라 (SSOT 참조)

- **서버/배포 인프라 SSOT: `D:\server-infra.md`** (로컬 전용, git 미추적 — 리포·운영서버에 없음)
- 포트·도메인·방화벽·컨테이너 TZ 규칙(`Asia/Seoul` 의무)·배포 반영 매트릭스(푸시 시 서버 자동/수동 반영 범위)·트러블슈팅은 전부 그 문서 참조.
- 리포별 `server-infra-*.md`는 폐지됨(2026-06-06). **인프라(compose/nginx/포트/배포) 변경 시 `D:\server-infra.md`를 함께 최신화할 것.**
