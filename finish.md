# finish.md — 포트폴리오 마무리 점검 (소스 · 인프라 · DB)

> 점검일: 2026-09-08 · 기준 커밋: `a760f67` (main) · 점검 범위: 이 저장소 + 로컬 빌드/테스트. 운영 서버 내부(nginx 설정, 백업 스크립트, `.env`)는 저장소에 없어 **미확인** 으로 표기.
>
> 마무리의 정의: "내가 없어도 굴러가고, 망가지면 되돌릴 수 있고, 왜 이렇게 생겼는지 설명이 남아 있으며, 이력서에 링크를 걸고 면접에서 설명할 수 있는 상태".
>
> 본문(§0~§5)은 2026-09-08 점검 원문이다. 이후 조치 결과는 아래 진행 현황에만 누적한다.

## 진행 현황 (최종 갱신 2026-09-14, 서버 점검 반영)

| §4 # | 작업 | 상태 | 근거 |
|---|---|---|---|
| 1 | 운영 DB 비밀번호 교체 | ✅ 2026-09-13 | 서버 작업(사용자 확인). `ALTER USER` root → `.env` `DB_PASSWORD` 갱신 → db·활성 색 app 재생성 → 구 비밀번호 접속 거부 확인. 앱 계정 분리(§3-2 ③)는 미실시 |
| 1 | tools fallback 제거 + README/install.bat 예시 정정 + `__pycache__`·`data/temp` 추적 해제 | ✅ | `09dcd01`. 실제 대상은 §1-2 ①의 4곳이 아니라 8개 파일(README 2개·`install.bat` 1개 추가)과 `.pyc` 14개(바이너리에도 비밀번호 포함). `git grep` 잔존 0건 |
| 2 | 운영 스키마 덤프로 `schema.sql` 교체, 구 migration SQL 삭제 | ✅ | `dc7a089` (29 테이블) |
| 2 | dev `ddl-auto=validate` + 기본 프로파일(`spring.profiles.active=dev`) 제거 | ✅ | `2bbb989`. 검증: 빈 DB에 `schema.sql` 적용 → dev 부팅 성공(HTTP 200) / 기존 로컬 `song` DB로도 부팅 성공 / 프로파일 없이 실행 시 DataSource 미설정으로 즉시 실패 / `./mvnw clean test` 316건 통과. 로컬 검증 DB는 MySQL 8.0(운영은 MariaDB 11.8) |
| 2 | Flyway baseline | 🔲 선택 | — |
| 3 | 백업 cron + 복원 리허설 | 🟡 리허설 ✅ 2026-09-14 / cron 🔲 | 수동 백업(`/root/backup/song-20260914-2104.sql.gz`, 31 테이블) → 임시 컨테이너 복원(적재 1.2초, 6개 표 행수 일치) → 복원 DB로 앱 기동 UP 33초·`validate` 통과. 자동 cron·오프사이트는 미착수 |
| 4 | runbook 초안 `docs/runbook.md` | 🟡 §1·§4-1·§4-2·§5(명령만)·§6 검증 2026-09-14 | 상태 확인·수동 롤백(`start`로 직전 색 복귀)·SHA 재배포·DB 백업/복원 리허설/운영 복원·재부팅 점검·비밀번호 교체·만료 항목·증상표. **§2 수동 롤백·§3 SHA 재배포는 미실시**(전환이 생기므로 저트래픽 시간에). 만료일은 TLS만 기입(2026-12-01), 나머지 🔲 |
| 10 | surefire `kill self fork JVM` 30초 대기 제거 | ✅ 2026-09-14 | `48a4be4`. 원인: `ThreadPoolTaskScheduler` 가 종료 시 cron 예약분(아직 시작 전)을 큐에 남겨 `awaitTermination(60s)` 를 다 채움 → `setExecuteExistingDelayedTasksAfterShutdownPolicy(false)`. 실행 중 배치는 계속 완료 대기. 단일 테스트 클래스 47초→25초, 전체 301건 통과·경고 0. 운영 검증 2026-09-15: `9c12679` 배포 시 구 색(green) 정상 종료 확인(사용자) |
| §2-3 | `docker image prune -f` 완화 | ➖ 불필요 판정 | `prune -f` 는 dangling 만 지워 SHA 태그 롤백 이미지는 원래 남는다(서버 실측 기록: 태그된 구 이미지는 0B). 실제 과제는 반대로 **SHA 이미지 누적** — 필요하면 최근 N개 보존 정리를 별도로 |
| §1-3 | Jest (`jest.config.js` + `src/test/javascript/auto-play-logic.test.js`) 삭제 | ✅ 2026-09-14 | 14건 통과하나 테스트 파일 안에서 `AutoPlayController` 를 재구현해 검증 — 운영 JS(`static/js`)를 import 하지 않고, `package.json` 이 gitignore 라 클린 체크아웃에서 실행 불가·CI 미연동 |
| — | `deploy.yml` `paths-ignore` `'*.md'` → `'**.md'` | ✅ 2026-09-14 | `*` 는 `/` 를 넘지 않아 `docs/runbook.md` 등 하위 경로 md 만 바꿔도 배포가 돌았음 |
| 5 | nginx 설정 저장소 반영 | ✅ 2026-09-14 | `infra/nginx/game.conf`·`quiz-upstream.conf` (서버 실측본). 서버의 untracked `nginx/nginx.conf`·`setup-https.sh`(3월 컨테이너 nginx 시절, 현행과 무관)는 삭제. **발견**: 호스트 nginx로 옮기며 WebSocket Upgrade 헤더 전달이 빠져 운영에서 WebSocket이 한 번도 성립한 적이 없었음(`/ws/websocket` 400, SockJS xhr 폴백은 CSRF 403 → REST 폴링으로만 동작). 서버 `game.conf`에 `location /ws/` 추가 후 101 확인 |
| 6 | README 정정 + `LICENSE` | ✅ | `e1b2c33` |
| 6 | CLAUDE.md 드리프트 정정 + README 숫자 재정정 | ✅ 2026-09-14 | 코드 재집계 기준: 배치 24(스케줄러 분기 24·seed 24), 서비스 22, 컨트롤러 client 13·admin 25, `@Entity` 29 + enum 3, 템플릿 69, 테스트 클래스 29. 상태 머신 `RoomStatus`(WAITING→PLAYING→FINISHED)·`RoundPhase`(PREPARING→PLAYING→RESULT), 장르 챌린지(50곡·라이프 5·HARDCORE만 랭킹), 팬 챌린지 20곡(HARDCORE 단계 20/25/30), 메모리 640M, compose 는 배포 시 `git pull` 동기화, CI/CD blue/green 흐름, 인프라 SSOT 경로. **추가 발견**: CLAUDE.md·README 가 인증을 `AdminInterceptor`/`SessionValidationInterceptor` 로 설명했으나 두 클래스는 Spring Security 전환(`68c9d74`) 때 제거됨 → `SecurityConfig` 기준으로 정정. `docker compose ... app` 명령도 blue/green 서비스명으로 정정 |
| 1-2 ② | ~~CLAUDE.md "배치 26개" → 실제 27개~~ | — | 3단계 삭제 후 24개 |
| 7 | 데드 코드 제거: 폐지·미등록 배치 3종, `GenreMigrationService`(+테스트), 미참조 템플릿 7개, pom tomcat 주석, Dockerfile `JAVA_OPTS` 주석 | ✅ 2026-09-14 | `23f6df8`. 삭제 전 재검증: 클래스·`BATCH_ID` 문자열·뷰 이름(컨트롤러 반환·MockMvc·JS) 참조 0건, `/admin/stats/popularity`·`/wrong-answers` 는 `redirect:` 확인. 검증: `./mvnw clean test` **301건** 통과(316 − `GenreMigrationServiceTest` 15), dev 부팅 성공. pom 빈 메타데이터는 `980640e`. 구 SQL 3개는 `dc7a089`에서 이미 삭제. **운영 DB `batch_config` 의 `BATCH_FAN_CHALLENGE_PERFECT_CHECK` 행은 서버에서 직접 삭제 필요**(남아 있으면 enabled=1 일 때 기동 WARN, 관리자 수동 실행 시 "실행할 수 없는 배치입니다" 오류) |
| 7 | `DAILY_MISSION.md` → `System.md` §16 개선 이력으로 흡수 후 삭제, `tools/test-data-30-challenge.sql` 삭제 | ✅ 2026-09-14 | §16 에 누락돼 있던 배치 쿼리 최적화 커밋 `1c010ff` 보강 |
| 7 | 운영 DB `batch_config` 잔존 행 | ➖ 불필요 판정 2026-09-14 | 서버 실측: 삭제한 3종 행 없음, 총 24행으로 seed와 일치. 기동 WARN 0건 |
| 7 | `SongFileCheckBatch`·`uploads` 볼륨 | 🔲 데드 코드 확정 2026-09-14 | 서버 실측: `quiz_uploads` 볼륨 파일 0개(8.0K), `BATCH_SONG_FILE_CHECK` enabled=0. 제거 대상: 배치 클래스 + `BatchScheduler`/`BatchService` seed 분기 + `file.upload-dir` + `WebConfig` 리소스 핸들러 + compose `uploads` 볼륨 + Dockerfile `mkdir` + `SecurityConfig` `/uploads/**` + `batch_config` 행. 볼륨 자체는 `down -v` 금지, `docker volume rm quiz_uploads`로 별도 |
| §3-2 | 운영 DB 잔재 테이블 `song_history`·`song_260113` DROP | ✅ 2026-09-14 | 코드 참조 0건(SongHistory 기능은 2026-01-15 추가·당일 제거 `9e06514`, `song_260113`은 01-13 수동 복사본). `/root/backup/song-legacy-tables-20260914-2104.sql.gz`로 보관 후 DROP → 테이블 29개 = `schema.sql` |
| 8 | 버전 1.0.0 / 태그 | 🔲 | — |
| 9 | uptime 모니터 + 배지 | 🔲 | — |

| 후속 | 코드 4건: `/ws/**` CSRF 예외 · `JAVA_TOOL_OPTIONS` `-Djava.security.egd=file:/dev/./urandom` · 없는 경로 404 처리 · `hibernate.dialect` 지정 제거 | ✅ 2026-09-15 | 2026-09-14 서버 로그·nginx 실측에서 발견. **404 건은 로그 위생이 아니라 응답 버그였음** — `NoResourceFoundException`이 `Exception` 핸들러에 잡혀 스캐너 요청에 500 에러 페이지를 돌려주고 있었다. 검증: 신규 테스트 4건(CSRF 예외는 수정 전 403 실패 확인), `./mvnw clean test` 305건 통과, dev 부팅 8초·dialect 경고 0. `9c12679` 배포 완료(2026-09-15 07:52, green→blue, 헬스 12회째). 외부 확인: `/robots.txt` 404(이전 200 에러페이지), `/.git/HEAD` JSON 404, `/ws/websocket` 101, `/actuator/health` 403. 서버 확인 2026-09-15(사용자): green 정상 종료·blue `SecureRandom` WARN 없음 — 이상 없음 |
| 후속 | 인프라: `game.conf` HSTS 없음(3월 컨테이너 nginx 설정에는 있었음) · SHA 이미지 14개 누적(디스크 18%, 급하지 않음) · `/root/backup` 구 평문 덤프 600 권한 적용 완료 | 🔲 | SSOT 백로그 |

**정정**: §1-2 ①이 "서버 공인 IP"라고 적은 tools의 기본 호스트 값은 현재 운영 VPS가 아니라 **별도 서버**의 IP다(인프라 기록 기준). 해당 서버에 과거 DB가 남아 있는지는 미확인.

---

## 0. 한 줄 결론

코드·CI/CD·테스트는 마무리에 가깝다. 그러나 **공개 저장소에 운영 DB 비밀번호와 서버 IP가 커밋되어 있고**, **운영 DB 스키마를 재현할 방법이 저장소에 없으며**, **README가 실제 코드와 여러 곳에서 어긋난다.** 이 세 가지를 먼저 닫아야 "이력서에 올릴 수 있는 상태"가 된다.

| 영역 | 상태 | 막고 있는 것 |
|------|------|-------------|
| 소스 | 🟡 거의 완료 | 시크릿 노출, README 드리프트, 데드 코드 잔재, 버전 태그 없음 |
| 인프라 | 🟡 거의 완료 | 롤백/재부팅/백업 절차가 저장소 밖에만 있음, 인프라 SSOT 문서 부재(이 PC 기준) |
| DB | 🔴 미완 | 스키마 정의 부재(32 엔티티 중 8개만 SQL), 백업·복원 검증 근거 없음, 비밀번호 유출 |

---

## 1. 소스 (코드)

### 1-1. 통과한 항목 ✅

| 조건 | 근거 |
|------|------|
| main 브랜치 클린, 미커밋 변경 없음 | `git status` clean, stash 없음, 원격 잔여 브랜치 1개(`fix/ci-docker-compose-v2`)는 main에 이미 병합됨 |
| 클린 빌드 + 테스트 통과 | `./mvnw clean test` (Java 17) → **316 tests, 0 fail, 0 error, 0 skip**, 30개 테스트 클래스 |
| CI가 테스트를 게이트로 사용 | `deploy.yml` build 잡에서 `clean test` 실패 시 배포 중단, surefire 리포트 아티팩트 업로드 |
| 앱 시크릿이 코드에 없음 | `BREVO_API_KEY`, `MAIL_FROM`, DB 자격증명 모두 `${ENV}` 주입. `.gitignore`가 `.env*`, `application-secret.properties` 차단 |
| 환경 의존값 외부화 | 호스트/포트/경로 하드코딩 없음 (`LoginRateLimiter`의 `127.0.0.1` 화이트리스트 기본값은 의도된 값) |
| 로깅 위생 | `System.out`/`printStackTrace` 0건, 비밀번호 로깅 없음 |
| 운영 seed 안전장치 | `DataInitializer`가 prod에서 기본 admin(`a@a.com/1234`)·테스트 회원 생성을 차단 |
| 의존성 버전 고정 | Spring Boot BOM 3.4.1, bucket4j 8.14.0 명시. 범위/LATEST 없음 |
| 보안 기본기 | Spring Security 폼 로그인 + CSRF + 세션 1개 제한, `/admin/**` ROLE_ADMIN, 레이트리밋 |
| TODO 잔재 최소 | 실질 TODO 1건 (`AdminFanChallengeController:229`) |

### 1-2. 반드시 닫아야 할 항목 🔴

**① 운영 DB 비밀번호·서버 IP가 공개 저장소에 커밋됨 (최우선)**

- 위치: `tools/song_checker_mcp/song_checker_mcp.py:44`, `tools/song_integrity_mcp/config.py:9-12`, `tools/song_register_mcp/song_register_mcp.py:53`, `tools/song_integrity_mcp/README.md:39`
- 내용: `os.environ.get('MARIADB_PASSWORD', '<실제 비밀번호>')` 형태의 **fallback 기본값으로 실제 비밀번호**가 들어 있고, `config.py`에는 서버 공인 IP와 DB 포트(3308)까지 기본값으로 박혀 있다.
- 이력: 첫 커밋(`6698a41`)부터 존재. 저장소는 GitHub **public**(`LeeKyuHyeong/quiz`)이다.
- 조치 (순서 중요):
  1. **서버에서 DB 비밀번호를 즉시 교체** (`.env`의 `DB_PASSWORD` 변경 → `docker compose up -d --force-recreate db app-*`). 이미 유출된 것으로 간주한다. 히스토리를 지워도 포크·캐시·크롤러에 남는다.
  2. 4개 파일에서 fallback 기본값을 제거하고, 환경변수 미설정 시 명시적으로 에러가 나게 한다. README 예시는 `YOUR_PASSWORD`로 통일.
  3. 히스토리 정리는 선택 사항이다. `git filter-repo`로 지우면 포트폴리오 링크로 들어온 사람이 `git log -S`로 찾는 일은 막을 수 있지만, force-push가 필요하고 이미 유출된 사실은 바뀌지 않는다. 1번을 했다면 2번까지만 해도 무방.
- 참고: compose가 DB 포트를 `127.0.0.1:3308`로 바인딩(2026-07-24)하고 있어 현재는 외부에서 직접 접속은 안 된다. 그래도 비밀번호는 `MYSQL_ROOT_PASSWORD`로도 쓰이므로 교체가 맞다.

**② README가 실제 코드와 어긋남 (면접에서 바로 걸리는 부분)**

| README 서술 | 실제 |
|------------|------|
| 패키지 `src/main/java/com/kh/quiz/` | `com.kh.game` |
| `application.yml` (dev/prod) | `application*.properties` 3개 |
| 멀티플레이 "HTTP Polling 기반" (2곳) | 2026-04-06에 STOMP/SockJS WebSocket으로 전환됨(`WebSocketConfig`, `ws-client.js`). Polling은 fallback |
| 엔티티 26개, 테이블 26개 | 엔티티 **32개** |
| 클라이언트 컨트롤러 40개/24개 (두 곳이 서로 다름) | client 13, admin 25 |
| 게임 모드 5가지 | **장르 챌린지**(`GenreChallengeRecord`, `GameGenreChallengeController`)가 코드에 있으나 README·CLAUDE.md·System.md 어디에도 없음 |
| "Playwright E2E 테스트" | `tests/`, `specs/`, `playwright.config.ts`, `package.json`이 전부 `.gitignore`. 저장소만 받아서는 실행 불가 → 존재 증명이 안 됨 |
| 상태 머신 `WAITING → PREPARING → PLAYING → FINISHED` | CLAUDE.md는 `ENDED`. 코드 기준으로 하나로 통일 필요 |
| License: MIT | `LICENSE` 파일 없음 |
| CLAUDE.md "배치 26개" | 실제 27개 (`BatchService` seed 27, `BATCH_ID` 27) |

→ README는 이력서에서 링크 클릭 후 처음 보는 화면이다. 위 표를 기준으로 한 번에 정정한다.

**③ 데드 코드 / 잔재**

- `FanChallengePerfectCheckBatch`: 2026-09-07 폐지(`@Deprecated`)됐지만 `BatchScheduler`에 여전히 주입·디스패치되고 seed에도 남아 있음. "롤백 대비"라는 사유는 있으나 포트폴리오 시점에는 삭제가 맞다.
- `FanChallengeBadgeGenerationBatch`, `FanChallengeBadgeMigrationBatch`: `BATCH_ID`는 있으나 `BatchScheduler`에 등록되지 않은 1회성 마이그레이션 배치. 실행 완료 후 남은 코드라면 제거.
- `tools/song_integrity_mcp/__pycache__/*.pyc` **14개가 git에 추적됨**. `.gitignore`에 `__pycache__/` 추가 후 `git rm --cached`.
- `pom.xml`: 주석 처리된 tomcat 의존성 블록, 비어 있는 `<licenses>/<developers>/<scm>` 메타데이터.
- `Dockerfile`: "다음 재빌드 때 제거"라고 적힌 주석 처리 `JAVA_OPTS` 라인.
- `DAILY_MISSION.md`: 2026-04-06까지의 작업 일지. 포트폴리오 문서로 남길지, System.md에 흡수할지 결정.
- 그 외 미참조 템플릿/정적 자원/클래스 후보: **아래 §1-4 참조.**

**④ 버전 표식이 없음**

- git 태그 0개, `pom.xml`은 `0.0.1-SNAPSHOT`, Docker 이미지는 `latest` + 커밋 SHA만.
- 조치: 마무리 시점에 `v1.0.0` 태그 + `pom.xml` 버전 `1.0.0` + 이미지에 `:v1.0.0` 태그. "운영 중인 버전이 무엇인가"에 한 단어로 답할 수 있어야 한다.

### 1-3. 권장 (선택) 🟡

- `spring-boot-devtools`가 `optional=true`로 남아 있다. 패키징된 WAR에선 자동 비활성화되므로 동작 문제는 없지만, 포트폴리오에선 "왜 있느냐"는 질문거리다.
- 테스트 종료 시 `Surefire is going to kill self fork JVM` 경고. 스케줄러/브로커 스레드가 non-daemon으로 남아 30초 대기 후 강제 종료된다. 실패는 아니지만 CI 시간 30초를 매번 버린다. `spring.task.scheduling.shutdown.await-termination=false` 또는 테스트 프로파일에서 `@EnableScheduling` 제외로 정리 가능.
- `src/main/resources/application.properties`의 `spring.profiles.active=dev` 기본값: 운영은 compose가 덮어쓰므로 안전하나, 실수로 프로파일 없이 띄우면 로컬 DB(root/1234)에 붙는다. 기본값을 비우고 명시 실행을 강제하는 편이 안전.
- Jest 설정(`jest.config.js`)과 `src/test/javascript/auto-play-logic.test.js`가 있으나 `package.json`이 gitignore되어 클린 체크아웃에서 `npm test`가 불가능. E2E와 같은 문제.

### 1-4. 미참조 자원 후보 (자동 탐색 결과)

삭제 전 각 항목은 `grep`으로 한 번 더 확인할 것. URL로만 도달하는 컨트롤러(`AdminChatController` 등)는 이미 제외했다.

**컨트롤러가 반환하지 않고 fragment로도 쓰이지 않는 템플릿 (7개)**

- `templates/admin/challenge/index.html` — `admin/challenge/fragments/*`로 대체됨
- `templates/admin/fan-challenge/list.html` — 컨트롤러는 `admin/challenge/fragments/fan-challenge` 반환
- `templates/admin/genre-challenge/list.html` — 컨트롤러는 `admin/challenge/fragments/genre-challenge` 반환
- `templates/admin/login-history/list.html` — 컨트롤러는 `admin/member/fragments/login-history` 반환. 이 파일이 참조하는 `/js/admin/admin.js`도 존재하지 않음
- `templates/admin/multi/index.html` — 컨트롤러는 `admin/multi/fragments/multi` 반환
- `templates/admin/stats/wrong-answers.html` — `AdminStatsController:47`이 `redirect:/admin/stats?tab=wrong-answers`로 대체
- `templates/admin/stats/popularity.html` — 어떤 컨트롤러도 이 뷰 이름을 반환하지 않음

**정적 자원 (CSS/JS)**: 미참조 없음. `css/admin/*.css`, `css/client/*.css` 일부는 템플릿이 아니라 `admin.css`/`client.css`의 `@import`로 로드되므로 유지.

**운영 코드에서 호출되지 않는 Java 클래스**

- `service/GenreMigrationService.java` — 테스트(`GenreMigrationServiceTest`)만 참조. 장르 마이그레이션이 끝났다면 클래스와 테스트를 함께 제거
- `batch/FanChallengeBadgeMigrationBatch.java` — `BatchScheduler` 미등록, `BATCH_ID`도 다른 곳에 없음. 런타임에 도달 불가
- `batch/FanChallengeBadgeGenerationBatch.java` — 위와 동일하게 미등록·미참조

**1회성 SQL / 시드 파일**

- `sql/migration_batch.sql`, `sql/migration_fan_challenge_difficulty.sql`, `sql/migration_multi_game.sql` — 어디서도 로드하지 않는 과거 DDL. §3-2 ①의 스키마 재정비와 함께 삭제
- `tools/test-data-30-challenge.sql` — 1회성 테스트 시드. 유지한다면 `tools/README`에 용도 명시

`*.bak`, `*.old`, `*copy*` 류 파일은 `target/` 밖에 없음.

---

## 2. 인프라

### 2-1. 통과한 항목 ✅

| 조건 | 근거 |
|------|------|
| 배포 자동화 (push → CI → 이미지 → 서버) | `deploy.yml`: 테스트 → WAR → Docker Hub push(`latest` + SHA) → SSH → blue/green 전환 |
| 무중단 배포 + 자동 롤백 게이트 | 유휴 색 기동 → `/actuator/health` 90초 폴링 → 실패 시 신규 컨테이너 정지·전환 안 함 → 성공 시 nginx upstream 재작성 + reload → 30초 드레인 후 구 색 정지 |
| compose 동기화 문제 해소 | 배포 스크립트가 `git pull --ff-only`로 compose를 자동 동기화 (CLAUDE.md의 "자동 동기화 안 됨" 경고는 이제 **구문**) |
| 재부팅 내성 | 전 컨테이너 `restart: unless-stopped`, named volume(`db-data`, `uploads`), healthcheck 정의 |
| 타임존 | Dockerfile `tzdata` + `TZ=Asia/Seoul` + compose `/etc/localtime` 마운트. 3중 보장 |
| 네트워크 노출 최소화 | app 8092/8093, DB 3308 모두 `127.0.0.1` 바인딩. 외부는 nginx만 |
| 리소스 상한 | app 640M / db 256M, JVM `MaxRAMPercentage=50`, 로그 로테이션 10m×3 |
| 이미지 재현성 | 커밋 SHA 태그로 pull 후 `latest` 재태깅 → 전파 지연 레이스 방지 |
| HTTPS | Nginx + Let's Encrypt (README 기준, 서버 설정은 미확인) |

### 2-2. 닫아야 할 항목 🔴

**① "되돌리는" 절차가 문서화되지 않음**

- 배포 실패 시 자동으로 전환하지 않는 것은 검증됐다. 그러나 **전환 후** 문제가 발견됐을 때 이전 색으로 되돌리는 명령(구 색 컨테이너 재기동 → upstream 파일 원복 → reload)이 어디에도 적혀 있지 않다. 스크립트가 구 색을 `stop`하므로 컨테이너는 남아 있어 되돌리기 자체는 가능하다.
- 조치: `docs/runbook.md`(또는 README 하단)에 다음 4가지를 명령어 수준으로 적는다: 수동 롤백, 특정 SHA로 재배포, DB 복원, 서버 재부팅 후 확인 절차.

**② 인프라 SSOT 문서가 이 PC에 없음**

- CLAUDE.md는 `D:\server-infra.md`를 단일 진실 원천으로 지정하지만 이 PC의 `D:\`에는 없다(집 PC 전용). nginx 설정(`/etc/nginx/conf.d/quiz-upstream.conf`, 서버 블록, `/actuator` 차단 규칙), 방화벽, 인증서 갱신은 **저장소 어디에도 없다.**
- 포트폴리오 관점에선 "서버가 사라지면 재구축 가능한가"에 답해야 한다. 최소한 nginx 설정 파일을 `infra/nginx/`로 저장소에 넣거나(시크릿 없음), SSOT 문서의 인프라 섹션을 저장소 `docs/`로 옮긴다.

**③ 관측·알림이 "확인 가능"한 수준이 아님**

- 헬스체크는 있지만, 컨테이너가 죽었을 때 사람에게 알리는 경로가 없다. `SystemReportBatch`가 일일 리포트를 만들지만 이는 앱이 살아 있을 때만 동작한다.
- 최소 조치: 외부 uptime 모니터(UptimeRobot 등 무료) 1개를 `https://game.kyuhyeong.com/`에 걸고 README에 배지로 노출. "운영 중"이라는 주장의 근거가 된다.

### 2-3. 권장 🟡

- `docker image prune -f`가 배포마다 실행되어 **롤백용 이전 이미지가 삭제될 수 있다.** SHA 태그 이미지는 dangling이 아니라 남지만, 확실히 하려면 마지막 N개 SHA를 보존하는 규칙을 적거나 prune을 `--filter until=72h`로 완화.
- `deploy.yml`의 `appleboy/ssh-action@v1.0.3`는 핀 버전이라 좋으나 다른 액션은 `@v5` 메이저 핀. 일관성 문제일 뿐 동작 이슈는 아님.
- `.dockerignore`가 `*.md`를 제외하므로 이미지에 문서가 들어가지 않는다. 좋다. 다만 `target/*.war`를 통째로 COPY하므로 로컬에 이전 WAR가 남아 있으면 잘못된 파일이 들어갈 수 있다. CI에서는 `clean`이 선행되니 문제 없음.
- 만료 관리: Let's Encrypt 자동 갱신 여부, Brevo API 키·Authorised IP, Docker Hub 토큰 만료일을 한 표로 정리해 두면 "1년 뒤에도 살아 있는 포트폴리오"가 된다.

---

## 3. DB

### 3-1. 통과한 항목 ✅

| 조건 | 근거 |
|------|------|
| 운영에서 스키마 자동 변경 차단 | prod `ddl-auto=validate` (첫 커밋부터) |
| DB 외부 노출 차단 | `127.0.0.1:3308` 바인딩, 외부는 SSH 터널만 |
| 기준 데이터 재생성 가능 | `DataInitializer`가 금지어·뱃지·메뉴·팬챌린지 단계 설정을 `count()==0`일 때 seed. `BatchService`가 27개 배치 cron 기본값 seed |
| 데이터 보존 정책이 코드로 존재 | 정리 배치 9개 (세션·방·채팅·게시판·로그인이력·배치이력·라운드시도·게임세션·신고) |
| 문자셋 | 서버·DB·JDBC URL 모두 utf8mb4 |
| 테스트 DB 분리 | H2 `MODE=MariaDB` + `create-drop` |

### 3-2. 닫아야 할 항목 🔴

**① 운영 스키마를 재현할 수단이 저장소에 없음 (DB 영역 최대 결함)**

- `src/main/resources/sql/schema.sql`은 **8개 테이블**(genre, song, song_answer, member, member_login_history, game_session, game_round, game_round_attempt)만 정의하고, DB 이름도 `song_quiz_dev/prod`로 현재 `song`과 다르다. 마이그레이션 SQL 3개를 더해도 32개 엔티티 중 절반 이하.
- prod는 `validate`이므로 Hibernate가 테이블을 만들지 않는다. 그런데 Flyway/Liquibase도 없다. 즉 **지금 운영 DB의 구조는 "언젠가 누군가 손으로 만든 것"이고, 그 과정이 저장소에 없다.** 새 서버에 이 저장소만 들고 가면 앱이 `validate` 실패로 뜨지 않는다.
- 조치 (둘 중 하나):
  - **(A) 최소**: 운영 DB에서 `mariadb-dump --no-data song > src/main/resources/sql/schema.sql`로 현재 스키마를 덤프해 기존 파일을 교체하고, 구 마이그레이션 SQL 3개는 삭제. README 로컬 실행 절차에 "schema.sql 적용" 단계 추가. dev도 `ddl-auto=validate`로 바꿔 스키마 드리프트를 조기에 잡는다.
  - **(B) 권장**: Flyway 도입. `V1__baseline.sql`에 (A)의 덤프를 넣고 `spring.flyway.baseline-on-migrate=true`. 이후 스키마 변경은 `V2__...sql`로 남는다. 포트폴리오에서 "스키마 이력을 코드로 관리한다"고 말할 수 있는 유일한 방법.

**② 백업이 존재하는지, 복원이 되는지 저장소에 근거가 없음**

- 저장소·CI·compose 어디에도 `mariadb-dump`, cron, 오프사이트 복사 흔적이 없다. 서버에 있을 수 있으나 **미확인**.
- 조치: 서버 cron으로 일일 `mariadb-dump` → gzip → 외부(오브젝트 스토리지 또는 최소한 다른 머신)로 복사. 그리고 **빈 컨테이너에 복원해 앱이 뜨는 것을 한 번 실행**하고 그 날짜를 runbook에 적는다. 백업 파일이 있다는 것과 복원이 된다는 것은 다른 명제다.

**③ 비밀번호 유출 (§1-2 ①과 동일 건)**

- 조치는 소스 섹션 참조. DB 관점에서 추가: 앱 계정과 root를 분리한다. 현재 compose는 `MYSQL_USER`와 `MYSQL_ROOT_PASSWORD`에 **같은** `DB_PASSWORD`를 쓴다. 앱 계정에는 `song` DB에 대한 DML만 부여하고 root 비밀번호는 별도 변수로.

### 3-3. 권장 🟡

- 느린 쿼리 점검 근거 없음. 랭킹·통계·`DailyStatsBatch`처럼 데이터가 쌓일수록 무거워지는 쿼리 3~5개에 `EXPLAIN`을 한 번 돌리고 결과(인덱스 사용 여부)를 System.md에 남기면 면접 소재가 된다.
- `MARIADB_AUTO_UPGRADE=1`은 편리하지만 메이저 업그레이드 시 되돌릴 수 없다. 이미지 태그를 `11.8`로 고정한 것은 좋다. 업그레이드 전 백업 절차와 묶어 runbook에 기록.
- `uploads` 볼륨: MP3 지원이 제거된(`40f97f1`) 이후에도 `SongFileCheckBatch`와 볼륨이 남아 있다. 실제로 파일이 없다면 배치·볼륨·`file.upload-dir` 설정을 함께 제거할 수 있는 데드 코드 후보.

---

## 4. 마무리 실행 순서 (제안)

우선순위는 "이력서에 링크를 걸어도 되는가"를 기준으로 잡았다.

| # | 작업 | 영역 | 검증 |
|---|------|------|------|
| 1 | 운영 DB 비밀번호 교체 + tools 4개 파일 fallback 제거 + README 예시 정정 + `__pycache__` 추적 해제 | 소스/DB | `grep -r "<구 비밀번호>"` 0건, 앱 재기동 후 로그인 정상 |
| 2 | 운영 스키마 덤프로 `schema.sql` 교체 (또는 Flyway baseline), 구 migration SQL 삭제, dev도 `validate` | DB | 빈 MariaDB에 schema.sql 적용 → dev 프로파일로 부팅 성공 |
| 3 | 백업 cron + 오프사이트 복사 + **복원 리허설 1회** | DB/인프라 | 복원한 DB로 앱 기동, 로그인·곡 조회 확인. 날짜를 runbook에 기록 |
| 4 | runbook 작성: 수동 롤백, SHA 재배포, DB 복원, 재부팅 후 점검, 만료 항목 표 | 인프라 | 문서만 보고 롤백을 한 번 실제 수행 |
| 5 | nginx 설정을 저장소(`infra/nginx/`) 또는 `docs/`에 반영 | 인프라 | 새 서버 재구축 시나리오를 문서로 따라갈 수 있는지 |
| 6 | README 정정 (§1-2 ② 표 전체) + `LICENSE` 추가 + 장르 챌린지 문서화 + E2E/Jest를 저장소에 포함하거나 README에서 삭제 | 소스 | README의 모든 숫자·경로가 `find`/`ls` 결과와 일치 |
| 7 | 데드 코드 제거: 폐지 배치 3종(`FanChallengePerfectCheck`, `FanChallengeBadgeMigration`, `FanChallengeBadgeGeneration`), `GenreMigrationService`, 미참조 템플릿 7개, 구 SQL 3개, pom·Dockerfile 주석 | 소스 | `./mvnw clean test` 통과 유지(테스트 수는 `GenreMigrationServiceTest` 삭제만큼 감소), 관리자 배치 페이지 정상 |
| 8 | `pom.xml` 1.0.0, git tag `v1.0.0`, 이미지 `:v1.0.0` push, README에 버전 명시 | 소스/인프라 | `git describe` = v1.0.0, 운영 `/actuator/info` 또는 로그에서 버전 확인 |
| 9 | 외부 uptime 모니터 + README 배지 | 인프라 | 배지 녹색 |
| 10 | (선택) `EXPLAIN` 3~5건 기록, surefire 30초 경고 제거, devtools 제거 | 소스/DB | — |

1~3번을 끝내면 "안전하고 재현 가능한 상태", 4~6번까지 끝내면 "이력서에 올릴 수 있는 상태", 7~9번까지 끝내면 "면접에서 어디를 찔려도 답이 있는 상태"다.

---

## 5. 이번 점검에서 확인하지 못한 것 (서버 접속 필요)

- nginx 설정 내용 (`/actuator` 외부 차단 여부, HSTS, 업로드 크기 제한)
- 서버 `.env`의 실제 변수 목록과 compose 요구 변수의 일치 여부
- 백업 cron 존재 여부, Let's Encrypt 갱신 타이머 상태
- 운영 DB 실제 테이블 수와 엔티티 32개의 일치 여부 (`validate`가 통과하고 있으니 일치할 가능성이 높으나 인덱스는 별개)
- 최근 GitHub Actions 실행 결과 (`gh` 미설치로 미확인, 마지막 push는 2026-09-08 02:16 UTC)
