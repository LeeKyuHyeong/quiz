# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 문서 지도

| 알고 싶은 것 | 위치 |
|---|---|
| 행동 원칙 원문·프로젝트 적용 예시 전체 | `docs/guides/llm-coding-guidelines.md` |
| 보안 검토 예시 코드·상세 체크리스트 | `docs/guides/security-review.md` |
| CSS 필수 템플릿·예시 코드 | `docs/guides/css-style.md` |
| 패키지별 클래스 목록·서비스 설명·배치 목록·점수표 | `docs/architecture-reference.md` |
| 설계 배경(동시성·WebSocket·예외·JPA·인프라·외부 API) | `System.md` |
| 멀티 참가자 접속 상태(창 닫기 나가기) 설계·결정 | `docs/multi-presence-design.md` |
| 서버 수동 절차(롤백·재배포·백업/복원·재부팅) | `docs/runbook.md` |
| 검증 기록·회귀 목록·미해결 항목 | `docs/verification/` (README 인덱스) |
| 2026-09-16 이전 마무리 점검·수정 이력 | `docs/finish.md` |

---

## 행동 원칙

속도보다 신중함. 사소한 작업(오타, 명백한 한 줄)은 유연하게.

1. **Think Before Coding** — 가정을 진술하고, 해석이 여럿이면 모두 제시하고, 불명확하면 묻는다. 더 단순한 방법이 있으면 말한다.
2. **Simplicity First** — 요청되지 않은 기능·추상화·유연성·불가능한 시나리오의 예외 처리를 넣지 않는다. "시니어가 오버엔지니어링이라 할까?"
3. **Surgical Changes** — 인접 코드·주석·포맷을 "개선"하지 않는다. 기존 스타일에 맞춘다. 내 변경으로 생긴 고아만 지운다. 무관한 dead code는 언급만. **변경된 모든 라인은 요청과 직접 연결되어야 한다.**
4. **Goal-Driven** — "버그 고쳐줘" = 재현 테스트를 만들고 통과시킨다. 다단계 작업은 `단계 → 검증 방법` 계획을 먼저 제시한다.

**이 프로젝트에서 먼저 확인할 것 (한 곳만 고치면 안 되는 곳)**
- 멀티플레이 상태 머신: `RoomStatus`(WAITING→PLAYING→FINISHED) + `RoundPhase`(PREPARING→PLAYING→RESULT) 2단. 한 phase만 고쳐도 `GameRoomService`·`MultiGameService` + `GameBroadcastService` push + REST 액션 + `ws-client.js`(polling fallback)가 함께 영향.
- 정답 판정: `AnswerValidationService`(정규화) + `AnswerGeneratorUtil`(영→한 음역) + `SongAnswer` 테이블(수동 정답) 3중. "답이 인정 안 돼요"는 어느 층인지 먼저 확인.
- 배치: 신규 배치는 클래스 + `BatchScheduler`의 `createTask`/`executeManually` 분기 + `BatchService` seed 세 곳을 함께.
- LP/티어: `MultiTierService` + `LpDecayBatch` + 매치 종료 LP 변동이 함께.
- "화면이 깨졌어요": 모드(Solo Guess/Host/Fan/Genre/Retro/Multi) × 테마(라이트/다크/`.game-page`) × 브레이크포인트(PC/768/480)를 먼저 특정.

**건드리지 말 것 (의도된 설계)**
- `common.css` 변수 시스템(`:root`, `[data-theme="dark"]`, `.game-page`).
- `SecurityConfig` `authorizeHttpRequests` 매처 순서(정적·`/ws/**`·`/auth/**`·`/admin/login` permitAll → `/admin/**` ADMIN → `/mypage/**` authenticated → 나머지 permitAll). 구 `AdminInterceptor`/`SessionValidationInterceptor`는 `68c9d74`에서 제거됨.
- Thymeleaf `fragments/header.html`·`footer.html` — 모든 화면이 의존.
- `docker-compose.yml` — 푸시하면 서버에 그대로 반영(아래 CI/CD). blue/green이 `x-app-common` 앵커 공유.
- `.github/workflows/deploy.yml` step 순서·path filter.
- `BatchConfig` cron 기본값 — 운영은 DB에서 조정.

**검증 패턴**
- Controller 추가 → `./mvnw test` + `MockMvc`로 200/4xx/5xx. Repository 메서드 → dev 실행 후 실제 row 확인. 쿼리 최적화 → `EXPLAIN` 전후 비교.
- Batch → `AdminBatchController` 수동 실행 → `BatchExecutionHistory` COMPLETED → 결과 테이블.
- 화면·CSS → dev(8082)에서 라이트/다크/`.game-page` × PC/768/480 = 9조합.
- 배포 → Actions 헬스체크·upstream 전환 로그 → 서버 `docker compose logs -f app-blue|app-green`(활성 색은 `/etc/nginx/conf.d/quiz-upstream.conf`) → 영향 엔드포인트 curl. 재기동은 `app`이 아니라 활성 색 서비스명.

**프로젝트 고유 규칙**
- 시크릿은 `application-secret.properties` 또는 `.env`(`BREVO_API_KEY`, `DB_PASSWORD`, `DOCKERHUB_TOKEN` 등). 절대 커밋 금지. 환경 의존 값(경로·호스트·포트·키)은 properties/환경변수로 외부화.
- 한국어 답변 OK. 변수명·함수명·커밋 메시지는 영어 또는 코드 컨벤션에 맞춘 한국어로 통일, 혼용 금지.
- 커밋 메시지에 `Co-Authored-By: Claude` 등 AI 트레일러 금지.

---

## ⚠️ 필수 준수 사항 (Quick Reference)

### CSS
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| 색상 | `#1e293b`, `rgba(0,0,0,0.5)` | `var(--text-primary)`, `var(--overlay-medium)` |
| 테마 | 라이트만 | 라이트 + 다크 + `.game-page` |
| 반응형 | PC만, 임의 브레이크포인트 | PC + 768px + 480px |
| 단위 | `width: 350px` | `width: 100%`, `max-width: 24rem` |
| z-index | `9999` | 계층값(모달 1000, 토스트 5000) |

### 보안
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| SQL | `"... WHERE title = '" + title + "'"` | `@Param` 바인딩, JPA 메서드 쿼리, 동적은 Specification |
| XSS | `th:utext="${userInput}"`, `innerHTML = userInput` | `th:text`, `textContent` |
| 인증 | POST에 검증 없음 | 모든 POST/PUT/DELETE에 인증+권한. 새 관리자 경로는 `/admin/**` 아래 |
| IDOR | ID만으로 접근 | 소유권 검증(`member.getId().equals(...)` 또는 `findByIdAndMemberId`) |
| CSRF | 토큰 없는 fetch | `th:action` 폼 자동, AJAX는 fetch 래퍼가 meta 토큰 첨부. 예외는 `/ws/**`·`/unload`뿐 |
| 로깅 | 비밀번호·개인정보 로깅 | id·이메일 정도만 |

### Thymeleaf
| 규칙 | ❌ 금지 | ✅ 필수 |
|------|--------|--------|
| 이벤트 핸들러 | `th:onclick="'fn(\'' + ${var} + '\')'"` | `th:data-x="${var}"` + `onclick="fn(this.dataset.x)"` |
| URL 파라미터 | `th:href="'/path?id=' + ${id}"` | `th:href="@{/path(id=${id})}"` |

### 빌드
- `mvn` ❌ → `./mvnw` ✅ · Java 17 필수(`JAVA_HOME`, 아래 경로)

---

## Build & Run

```bash
# JDK 17 (기본 java가 17이 아니라 반드시 지정)
export JAVA_HOME="/c/Program Files/Java/jdk-17"          # 집 PC (기본 21)
# export JAVA_HOME="$HOME/.jdks/corretto-17.0.12"        # 회사 PC (기본 8)

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev    # 8082. 프로필 기본값 없음, 생략 불가
./mvnw clean package -DskipTests                          # WAR
./mvnw test                                               # 전체 (H2, 로컬 DB 불필요)
./mvnw test -Dtest=GameRoomCapacityTest                   # 클래스 / #메서드
```

운영 로그: `docker compose logs -f app-blue`(또는 `app-green`). `app` 서비스는 없다.

---

## Architecture

Spring Boot 3.4.1 + Java 17 + JPA + MariaDB 11.8 + Thymeleaf + STOMP/SockJS. `Controller(MVC+REST) → Service → Repository(JPA) → MariaDB`, 화면은 Thymeleaf SSR.

`com.kh.game` 패키지: `controller/client`(13) · `controller/admin`(25) · `service`(23 + `BrevoMailClient`) · `entity`(`@Entity` 29 + enum 3) · `repository` · `batch`(24, `BatchScheduler`) · `config`(`SecurityConfig`, `WebSocketConfig`+`WebSocketAuthInterceptor`, `DataInitializer` 등) · `security`(`CustomUserDetails*`, 로그인 핸들러, `LoginRateLimiter`) · `exception`(`GlobalExceptionHandler`) · `util`(`AnswerGeneratorUtil`, `SecurityInputValidator`, `JunkInputFilter`) · `dto`(`GameSettings`, `WebSocketMessage`). 클래스 목록은 `docs/architecture-reference.md`.

### 게임 모드
1. **Solo Guess** — 3회 시도. RANDOM/FIXED_GENRE/FIXED_ARTIST/FIXED_YEAR/라운드별 선택. 30곡 도전 모드는 랭킹 반영.
2. **Solo Host** — 호스트가 힌트를 읽고 다른 사람이 맞힘.
3. **Fan Challenge** — 아티스트 20곡(`CHALLENGE_SONG_COUNT`). NORMAL은 20곡 1단계, HARDCORE만 단계제(20/25/30, `FanChallengeStageConfig`)·랭킹 반영. 퍼펙트 클리어 추적.
4. **Genre Challenge** — 장르 50곡(`GenreChallengeService` MIN/MAX). HARDCORE만 랭킹 반영.
5. **Retro** — `releaseYear < 2000` 또는 장르 `RETRO`.
6. **Multiplayer** — 방 기반. 서버→클라이언트 STOMP push, 액션은 REST POST, 연결 실패 시 polling. 첫 정답 100점. LP 티어(Bronze→Challenger).

### 점수·난이도 (출처: enum·상수)
| 항목 | 값 |
|---|---|
| Solo 30곡 도전(시간) | 0-5초 100 · 5-8초 90 · 8-12초 80 · 12-15초 70 · 15초+ 60 · 3실패 0 |
| Solo casual / Host / Multi | 10→7→5 / 100→70→50 / 첫 정답 100 |
| Fan Challenge (`FanChallengeDifficulty`) | NORMAL 재생 7s·답 6s / HARDCORE 5s·5s. 생명 3. 초성 힌트 없음. 랭크는 HARDCORE만 |
| Genre Challenge (`GenreChallengeDifficulty`) | NORMAL 7s·6s / HARDCORE 5s·5s. 생명 5. 랭크는 HARDCORE만 |

### 데이터 흐름
`GameSession → GameRound → GameRoundAttempt`(솔로) · `GameRoom → GameRoomParticipant, GameRoomChat`(멀티) · `Song → SongAnswer`(정답 변형) · `Board → BoardComment, BoardLike` · `Member → MemberBadge → Badge` · `FanChallengeRecord`/`GenreChallengeRecord`(난이도·단계·점수) · `RankingHistory`(일일 스냅샷) · `MenuConfig`(메뉴)

### Multiplayer Flow (변경이 잦은 규칙 — 여기가 단일 출처)
1. 방 생성 → 참가 → 준비. 생성 본문은 **통째로 `GameSettings`로 바인딩** — JS 키는 DTO 필드명(`privateRoom`, 모드 필드 최상위)과 같아야 하며 모르는 키는 Jackson이 조용히 버린다(`MultiGameControllerRoomLifecycleTest` 계약 테스트). 비공개 방 = 로비에 🔒로 보이되 코드 미노출, 코드로만 입장, 비밀번호 없음(`GameRoom.password` 미사용).
2. 방장 시작 → 방 `PLAYING`(round phase는 null). 3. 라운드 시작 → `PLAYING`(`PREPARING`은 어디서도 설정되지 않음). 4. 첫 정답 → `RESULT` → 다음 라운드 또는 방 `FINISHED`.

- **push payload = 폴링 응답 형태.** `ROOM_UPDATE`는 `GameRoomService.buildRoomStatus`(`success` 키가 없어 클라이언트는 `success === false`만 "방 종료"로 봄), `CHAT`은 `MultiGameService.toChatInfo`(GET `/chats` 항목과 동일: `id`·`memberId`·`isHost`·`messageType` `CORRECT_ANSWER`). 시스템 메시지(`addSystemMessage`)도 저장 직후 `CHAT` push(WS 사용자는 채팅 폴링 안 함).
- 구독·폴링 GET(`/round`·`/chats`)·액션 POST(`/chat`·`/skip-vote`)는 활성 참가자(JOINED/PLAYING)만. `/status`는 참가 전 미리보기라 열려 있음.
- **참가자 행은 지우지 않고 `LEFT`로만 바꾼다.** 정원(`getCurrentPlayerCount`, `findAvailableRooms`)은 LEFT 제외 — `SIZE(r.participants)`를 쓰면 나간 자리를 못 채운다(`GameRoomCapacityTest`).
- **나가기 4종:** ① `/leave`(CSRF) — WAITING·PLAYING 모두 실제로 나감, 방장이면 위임, 마지막이면 종료, FINISHED는 무시(재시작 지원). ② `/leave-to-lobby` — FINISHED에서 LEFT. ③ `/unload`(`sendBeacon`, **이 경로만 CSRF 예외**) — `pagehide` 에서만, 그 페이지의 `unloadToken` 을 싣는다. `RoomUnloadService`는 그 참가자의 **최신** 페이지 토큰일 때만 `game.multi.unload-grace-ms`(기본 8000) 뒤 적용하고, 그 사이 대기실·플레이·결과 GET(새 토큰 발급)이나 재참가면 취소. **브라우저는 새 페이지를 받은 뒤 옛 페이지의 pagehide 를 실행하므로 "다음 GET 이 취소한다"만으로는 안 된다**(2026-09-21 운영: 게임 시작 8초 뒤 전원 이탈) — 토큰이 옛 페이지 신호를 거른다. 토큰 없음·모르는 토큰(배포 전 페이지)은 무시. 토큰·대기 목록은 인메모리. ④ **연결 끊김(O-018)** — `RoomPresenceService` 가 방 토픽 구독을 `(방, 회원)`별 세션 집합으로 세고, 집합이 비면 `game.multi.disconnect-grace-ms`(기본 60000) 뒤 나가기(창 전체 닫기·강제 종료·네트워크 끊김). 판단은 도착 순서가 아니라 "집합이 비었는가". 재구독·페이지 GET·재참가가 취소하고, 폴링 GET(`/status`·`/round`·`/chats`)은 **끊김으로 잡힌 나가기만** 취소(폴링 폴백 사용자 보호). 끊김은 탭 닫기 신호의 예약을 늦추지 않는다. 결과 화면은 방장도 구독하고, 재시작 때 연결 없는 참가자의 나가기를 다시 잡는다(종료된 방에선 `leaveRoom` 무시라서). 종료 중 프로세스의 끊김은 무시(배포 드레인 30초 < 60초). STOMP 하트비트 `{game.multi.ws-heartbeat-server-ms=10000, ...-client-ms=30000}` → 멈춘 연결은 3×30=90초에 끊김 — 클라이언트 간격을 10초로 줄이지 말 것(크롬 가려진 탭 타이머 1분 묶임). 방치된 PLAYING 방은 `RoomCleanupBatch`가 2시간 뒤 종료.
- `nextRound`·`skipCurrentSong`·`startRound`는 방이 PLAYING일 때만 — FINISHED에서 재호출되면 `finishGame`이 다시 돌아 전적·LP 이중 반영.

### 알아야 할 서비스 규칙
- **정답 판정**: 정규화(소문자, 공백·특수문자 제거, 영숫자+한글만) 후 `Song.title`과 `SongAnswer` 모두 비교. `AnswerGeneratorUtil`이 영→한 음역 변형 생성(~700 단어 표).
- **YouTube 검증**: oEmbed → 썸네일 크기 2단계(삭제 영상 감지). **금칙어**: `ConcurrentHashMap` 캐시, 변경 시 자동 리로드.
- **DataInitializer**: `count()==0`일 때 메뉴·금칙어·뱃지·팬 챌린지 단계 seed. 기본 admin·테스트 회원은 prod에서 생성 안 함.
- **메일**: `BrevoMailClient` 한 곳(HTTPS, cafe24가 SMTP outbound 차단). 인증 코드·임시 비밀번호 모두 경유. `EmailVerificationService`는 가입·비밀번호 재설정 공용(`email_verification` 한 테이블).
- **비밀번호**: BCrypt라 "찾기" 없음. 관리자 초기화(`/admin/member/reset-password/{id}`)는 12자 임시 비밀번호를 **메일 성공 후에만** 저장(본인 불가). 본인 재설정(`/auth/password-reset`)은 코드 인증 → 저장 → `MemberSessionService`로 세션 만료.
- **LoginRateLimiter**: IP별 분당 20회(bucket4j), 화이트리스트 `security.rate-limit.whitelist`, 10분 유휴 버킷 제거. 로그인(`LoginAttemptFilter`·관리자 폼)·가입·이메일 인증에 적용. **클라이언트 IP 는 `resolveClientIp`(= `getRemoteAddr()`)만 쓴다 — 프록시 헤더를 직접 읽지 말 것**(운영은 `forward-headers-strategy=native`). **LoginAttemptService**: 계정당 연속 5회 실패 → 5분 잠금, `member.login_fail_count`·`login_locked_until` 은 쿼리로만 변경. 인증 메일은 주소당 60초에 한 번. **SecurityInputValidator**: 이메일 정규식 + SQLi 패턴 차단 → 400.
- **티어**: Bronze→Challenger, 티어당 LP 0-100, ELO 기반 변동(순위·인원·상대 티어차). **뱃지**: 카테고리 BEGINNER/SCORE/VICTORY/STREAK/TIER/SPECIAL, 희귀도 COMMON/RARE/EPIC/LEGENDARY, `BadgeAwardBatch` 또는 `BadgeService`.
- **배치 24개**: `BatchConfig` 테이블 cron으로 제어(`BatchService`가 기본값 seed). 정리 9 · 통계/랭킹 4 · 회원 4 · 곡 무결성 5 · 팬 챌린지 1 · 시스템 1. 목록은 `docs/architecture-reference.md`.
- **게시판**: 카테고리 REQUEST/OPINION/QUESTION/FREE, 상태 ACTIVE/DELETED/HIDDEN.

---

## Configuration

- **Profile:** 기본값 없음. 로컬 `-Dspring-boot.run.profiles=dev`, 운영은 compose `SPRING_PROFILES_ACTIVE=prod`.
- **dev:** 8082, MariaDB `localhost:3306/song`, `ddl-auto=validate`. **prod:** 환경변수 자격증명, Docker 볼륨. **test:** H2 `MODE=MariaDB`, `create-drop`.
- **Schema:** `src/main/resources/sql/schema.sql`이 단일 출처(dev·prod `validate`, Flyway 없음). **엔티티 변경 시 schema.sql 수정 + 로컬·운영 DB에 직접 ALTER**해야 기동한다.
- **Admin:** `Member.role=ADMIN`, `/admin/**` → `hasRole("ADMIN")`. 세션 유휴 60분(열린 탭의 상태 확인은 연장하지 않음, 멀티 WS 연결 중에는 keepalive), 1계정 1세션.
- **File uploads:** `uploads/songs/` 50MB — MP3 지원 제거 후 데드 코드. `Song.file_path`·`GameRoom.password`와 함께 스키마 변경 동반이라 보류(`docs/finish.md` 7, open-issues O-005).
- **Docker memory:** App 640M ×2(blue/green, 평시 한 벌, JVM `MaxRAMPercentage=50`), DB 256M.

### 운영 `.env` 필수 항목
| 변수 | 용도 |
|---|---|
| `DOCKERHUB_USERNAME` | 이미지 pull |
| `DB_USERNAME` / `DB_PASSWORD` | MariaDB 자격 증명 |
| `BREVO_API_KEY` | Brevo API 키(`xkeysib-...`). **대시보드 Security → Authorised IPs에 서버 IP 등록 필수**, 미등록 시 전부 401 |
| `MAIL_FROM` | 발신 주소(Brevo 사전 검증 필수) |

`docker-compose.yml`은 배포 스크립트의 `git pull --ff-only`로 서버에 자동 반영된다. 서버 `.env`는 git 미추적이라 환경변수 추가·변경 시 서버 `.env`를 먼저 고친 뒤 푸시한다.

## CI/CD (`.github/workflows/deploy.yml`)

- 트리거: main push 또는 수동. `**.md`·`.claude/**`·`.gitignore`·`LICENSE`만 바뀌면 안 돎.
- **build**: JDK 17 → `./mvnw clean test`(실패 시 중단, surefire 리포트 업로드) → WAR → Docker 이미지 push(`latest` + SHA).
- **deploy**(SSH, blue/green): `git pull --ff-only` → SHA pull 후 `latest` 재태깅 → nginx upstream(`/etc/nginx/conf.d/quiz-upstream.conf`)으로 활성 색 판별 → 유휴 색 기동 → `/actuator/health` 90초 폴링(실패 시 신규 정지, 전환 안 함) → upstream 재작성 + `nginx -s reload` → 30초 드레인 후 구 색 정지.
- Secrets: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_PORT`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.
- 테스트 JVM 시간대는 surefire `argLine`으로 `Asia/Seoul` 고정.

## 보안 검토 (요약)

모든 기능은 `설계 → 구현 → 보안 검토 → 테스트 → 리뷰`. Quick Reference 표 + 아래를 확인하고, 예시와 상세 체크리스트는 `docs/guides/security-review.md`.

- 새 관리자 경로가 `/admin/**` 아래인가. 새 POST에 인증·권한·소유권 검증이 있는가. 비밀번호 변경은 현재 비밀번호 확인.
- 파일 업로드는 확장자 화이트리스트 + UUID 파일명 + 웹 루트 밖 저장.
- 로그인 성공 시 세션 ID 재생성(Spring Security 기본). 로그·에러 응답에 개인정보·스택트레이스 노출 금지.
- 반복 호출 가능한 경로(로그인·가입·인증 메일)는 `LoginRateLimiter` 대상인지 확인.

## CSS (요약)

색상은 변수만. 라이트/다크/`.game-page` 1:1. 반응형은 480/768 두 브레이크포인트(`game-multi.css`만 900px 허용). 필수 템플릿과 예시는 `docs/guides/css-style.md`.

- 테마 구조: `common.css`의 `:root`(라이트) → `[data-theme="dark"]` → `.game-page`(항상 다크). `.game-page`에서 흰 배경 요소에 `var(--text-primary)`를 쓰면 흰 글씨가 된다 → 다크 섹션에서 별도 처리.
- 주요 변수: `--text-primary`·`--text-secondary`·`--text-muted` / `--bg-base`·`--bg-surface`·`--bg-elevated` / `--border-color` / `--overlay-*`(투명도). 새 색은 `:root`에 변수 추가 후 사용.
- z-index: 기본 1-10 · 고정 100 · 드롭다운 500 · 모달 배경 900 · 모달 1000 · 토스트 5000 · 뱃지 토스트 10000.
- 단위: `%` → `vw/vh` → `rem` → `px`(최후, `1px` 보더만). radius 0.25/0.5/0.75/1rem/50%. spacing 0.25rem 단위. transition 0.2/0.3/0.5s.

## 서버 인프라 (SSOT 참조)

- 인프라 SSOT: `D:\dev\career\03-infra\01-vps.md`(비공개, 이 리포·서버에 없음). 포트·도메인·방화벽·TZ(`Asia/Seoul` 의무)·트러블슈팅은 그 문서.
- nginx 설정 재구축용 사본 `infra/nginx/`(`game.conf`·`quiz-upstream.conf`). 서버 원본이 진실, 배포 스크립트는 `quiz-upstream.conf`만 다시 쓴다. `location /ws/` Upgrade 블록이 없으면 WebSocket 불성립.
- 서버 수동 절차는 `docs/runbook.md`. **인프라 변경 시 그 문서도 함께 최신화.**

## 검증 설정

> 전역 `~/.claude/CLAUDE.md`의 검증 규칙(AC → 검증 실행 → 기록)이 이 저장소에 적용될 때의 값. 검증 기록은 `docs/verification/`.

- 유형: 본인 작성·운영 중(전역 onboarding §1 특성 테스트 절차 해당 없음).
- 명령: 위 Build & Run. 전체 테스트는 H2라 로컬 DB 불필요, 2026-09-22 기준 124 클래스(57 파일) 449건. 로컬 실행만 MariaDB `song` 필요.
- 사용자 시나리오: 수동 체크리스트(Playwright 스펙은 저장소 미포함). 멀티는 브라우저 2개 + 시크릿 창(참가자 B·비참가자 C).
- 테스트 계정(dev, `DataInitializer`, prod 미생성): 관리자 `a@a.com`(ADMIN) · 일반 `test1@test.com`~`test6@test.com`(USER). 비밀번호는 코드에만.
- 외부 연동: Brevo는 `@MockBean`/`@Mock`(`AuthControllerPasswordResetTest`, `MemberServicePasswordTest`), 실제 발송은 운영 반영 후 🙋. YouTube는 Mockito(`YouTubeValidationServiceTest`, `YouTubeVideoCheckBatchTest`). DB는 H2 `MODE=MariaDB`라 MariaDB 전용 SQL은 테스트에서 못 잡는다.
- 배포 후 Smoke: `docs/runbook.md` §1 + 영향 엔드포인트 curl.
- 기록: `docs/verification/`(README 인덱스 · `records/` · `regression-list.md` · `open-issues.md`). 2026-09-16 이전은 `docs/finish.md`.

### P0 핵심 시나리오 (2026-09-16 확정)
1. 인증: 회원가입(이메일 코드) → 로그인 → 비밀번호 재설정. 세션 1개 제한·레이트리밋·`redirect` 내부 경로만
2. 멀티: 방 생성 → 참가 → 준비 → 시작 → 정답 → 결과 → 나가기 3종. LP·전적은 1회만 반영
3. 솔로 30곡 도전: 3회 시도·시간 점수 → 세션 저장 → 랭킹 반영
4. 팬/장르 챌린지 HARDCORE: 기록 저장 → 랭킹·뱃지
5. 관리자: 곡 등록/수정/소프트 삭제 → 게임 곡 풀 즉시 반영, 정답 변형 생성
6. 배치: 관리자 수동 실행 → `BatchExecutionHistory` COMPLETED

P1: 랭킹 스냅샷·주/월 리셋, 게시판, 곡 신고, 관리자 회원 관리 · P2: 통계, 메뉴 설정, 금칙어 · P3: 문구·CSS

### 이 프로젝트만의 규칙
- 엔티티 변경 = `schema.sql` + 로컬·운영 DB ALTER. 검증 기록의 "DB·설정 변경"에 반드시 적는다.
- 멀티는 push payload = 폴링 응답 계약 유지(`MultiGameControllerChatPushTest`, `MultiGameControllerRoomLifecycleTest`).
- JS 수정은 Maven 밖 → 수동 시나리오나 스크래치 하네스, 기록에 방법 명시.
- 버그 수정은 실패 테스트 선행 → `regression-list.md` 1행 추가.
