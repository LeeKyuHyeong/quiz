# N+1 제거 Part 1 — `Song.genre`(관리자 곡·정답 목록) · `Member.selectedBadge`(랭킹) (career 【확인 43】)
- 일자: 2026-10-13
- 유형: 수정 (JPA fetch 전략 — 스키마 변경 없음)
- 우선순위: P2 (관리자 목록·랭킹 조회. 데이터 변경 없음)
- 판정: 조건부 — 코드·재현 테스트 전후·렌더링 가드·OSIV 실험·전체 회귀 ✅, 운영 배포·Smoke 🙋(push 뒤)

## 1. 요청과 목적
- 사용자가 원한 것: career VGA §4-2 【확인 43】 — EAGER 연관 3곳 중 **Part 1 = `Song.genre` + `Member.selectedBadge`** 를 EAGER→LAZY + 목록 쿼리 fetch join 으로 바꾸고, "목록 1 + 행마다 1" 을 **숫자로 재현한 테스트가 수정 전 실패 → 후 통과**하게 한다. `GameRound.song/genre`(호출처 62곳, `Song.genre` 연쇄) 는 Part 2(O-034).
- 개발자 확인 결과(결정 사항, 2026-10-13): ① Part 1 범위 위와 같음 ② 방식 (a) EAGER→LAZY + `LEFT JOIN FETCH`/`@EntityGraph` + `countQuery` 분리 ③ 랭킹 `List<Member>` 쿼리 **전부** fetch join(안 건 메서드는 렌더 때 다시 N+1), `default_batch_fetch_size` 는 넣지 않음(기법 하나로 서사 단순) ④ OSIV 기본값(true) 유지, 기동 WARN·끈 상태 실험은 기록만 ⑤ 운영 응답시간(실험 4)은 nginx `log_format` 에 `$request_time` 이 있을 때만.
- 진행 중 둔 가정: 없음. (관리자 정답 목록 `findAll(pageable)`·키워드 검색도 장르 열을 그리므로 Part 1 에 포함 — VGA §4-2 가 `SongService.findAll(pageable)` 을 대상으로 명시.)

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 관리자 곡 목록 20건(장르 5종) 조회 SQL 이 목록 1 + count 1 = **2 번**. 수정 전 ❌ → 후 ✅ | ✅ | `NPlusOneQueryCountTest$AdminSongList` 4건 — 수정 전 **7**(= 2 + 장르 5) ❌, 후 **2** ✅ (§5) |
| 2 | 정상 | 랭킹 첫 목록 12명(뱃지 4종) 조회 SQL 이 **1 번**. 수정 전 ❌ → 후 ✅ | ✅ | `NPlusOneQueryCountTest$Ranking#guessRankingByScore_isOneStatement` — 전 **5**(= 1 + 뱃지 4) ❌, 후 **1** ✅ |
| 3 | 정상 | 곡 목록의 장르명·정답 목록의 장르명·랭킹 API 의 뱃지 이모지/이름이 수정 전과 같이 나온다 | ✅ | `LazyAssociationRenderingTest` 3건(MockMvc, **@Transactional 없이** — 요청 세션만으로 렌더) + dev 서버 `/api/ranking` id 17 → 👑 만점왕 (§5) |
| 4 | 예외 | 장르 없는 곡·뱃지 없는 회원이 목록에서 빠지지 않는다(LEFT JOIN) | ✅ | `songWithoutGenre_isStillListed`(총 21건, "장르없음" 포함) · `memberWithoutBadge_isStillRanked`(12명, 1위가 뱃지 null) |
| 5 | 경계 | 페이징 `totalElements` 가 fetch join 뒤에도 같다(countQuery 분리) | ✅ | 위 `songWithoutGenre_isStillListed` totalElements 21 + 기존 `SongRepositoryTest` searchWithFilters 11건 통과 |
| 6 | 연쇄 | LAZY 전환 뒤 OSIV 밖 경로(배치·STOMP·스케줄러)에서 `LazyInitializationException` 없음 | ✅ | §4 호출처 전수표 — 비요청 스레드에서 `getGenre()`/`getSelectedBadge*()` 에 닿는 경로는 전부 클래스/메서드 `@Transactional` 안. 실험 3 에서 수정 대상 4 경로는 OSIV 를 꺼도 200 |
| 7 | 회귀 | 전체 테스트 통과 | ✅ | **507 run · 0 failures · 0 errors · 0 skipped**(기존 497 + N+1 7 + 가드 3. Docker 기동 중이라 Redis 계약 테스트 포함) (§5) |
| 8 | 정상 | 운영 배포 뒤 `/admin/song`·`/ranking`·`/api/ranking` 정상, 뱃지·장르 표시 동일 | 🙋 | push → Actions → Smoke(§6) |

## 3. 변경 사항
- `src/main/java/com/kh/game/entity/Song.java` — `genre` `FetchType.EAGER → LAZY` (+주석: 목록 쿼리는 저장소에서 fetch)
- `src/main/java/com/kh/game/entity/Member.java` — `selectedBadge` `EAGER → LAZY`
- `src/main/java/com/kh/game/repository/SongRepository.java` — `searchWithFilters`·`searchWithFiltersMultipleArtists`: `SELECT s FROM Song s LEFT JOIN FETCH s.genre WHERE …` + **`countQuery = SELECT COUNT(s) FROM Song s WHERE …`** 명시 — **실측(10-13): countQuery 를 빼도 `totalElements` 테스트가 통과한다**(Spring Data JPA 3.4.1 이 fetch 를 걷어낸 count 를 스스로 만듦). 그래도 count 에 불필요한 join 이 끼지 않게 명시해 둔다(T-6 때와 달리 "필수" 가 아니라 "명시") · `findAll(Pageable)` override + `findByTitleContainingOrArtistContaining` 에 `@EntityGraph(attributePaths = "genre")`(관리자 정답 목록)
- `src/main/java/com/kh/game/repository/MemberRepository.java` — `List<Member>` 를 돌려주는 랭킹 쿼리 **33개** 전부 `SELECT m FROM Member m LEFT JOIN FETCH m.selectedBadge WHERE …`(COUNT·nickname 쿼리는 제외). 죽은 쿼리 3개(`findTopByTotalScore`·`findTopByAccuracy`·`findTopByTotalGames`, 호출처 0)도 같은 모양으로 — 정리는 별도.
- `src/test/java/com/kh/game/repository/NPlusOneQueryCountTest.java` — 신규 7건. `@DataJpaTest` + `hibernate.generate_statistics=true`(테스트 프로퍼티만) → `Statistics.getPrepareStatementCount()` 로 SQL 수를 단언. `em.flush(); em.clear(); statistics.clear()` 뒤부터 센다.
- `src/test/java/com/kh/game/controller/LazyAssociationRenderingTest.java` — 신규 3건. **가드 테스트**(LAZY 가 화면·API 를 깨지 않는지). 구현 뒤 작성이라 바로 통과했다 — 이 테스트가 떨어지는 조건은 "fetch join 이 빠지고 OSIV 도 꺼진 상태"뿐(실험 3).
- DB·설정 변경: **없음**(schema.sql·운영 DB ALTER 불필요 — 인덱스 작업과 달리 선반영 단계 없음). OSIV 설정도 그대로(기본 true).

## 4. 영향 범위 분석
- `Song.getGenre()` 호출처 **47곳 / 10파일**(main) — 전부 요청 스레드(OSIV) 또는 `@Transactional` 안:

| 파일 | 건수 | 실행 문맥 | LAZY 안전 근거 |
|---|---|---|---|
| `controller/client/GameHostController` | 12 | MVC 요청 | OSIV(기본 true) — 요청 끝까지 세션 유지 |
| `controller/client/GameGuessController` | 9 | MVC 요청 | 〃 |
| `controller/client/RetroGameController` | 6 | MVC 요청 | 〃 |
| `controller/admin/AdminGenreChallengeController` | 5 | MVC 요청 | 〃 |
| `controller/admin/AdminSongController` | 1 | MVC 요청 | 〃 + 목록은 fetch join |
| `service/SongService` | 8 | 서비스 | 클래스 `@Transactional` |
| `service/MultiGameService` | 2 (`getCurrentRoundInfo` :590) | **STOMP push·`RoomUnloadService` 스케줄러에서도 호출** | 클래스 `@Transactional(readOnly = true)` — 다른 빈에서 호출하므로 프록시를 타고 자체 트랜잭션이 열린다 |
| `service/FanChallengeService` | 1 | 서비스 | 클래스 `@Transactional` |
| `service/GenreChallengeService` | 1 | 서비스 | 클래스 `@Transactional` |
| `batch/SongAnalyticsBatch` | 2 | **배치 스레드(OSIV 없음)** | `execute()` 메서드 `@Transactional(readOnly = true)` |

- `Member.getSelectedBadge()`/`getSelectedBadgeEmoji/Name/Color()` java 호출처: `RankingController` :304~306(MVC)뿐. 템플릿: `client/board/list.html:63`·`detail.html:24,69` 가 `board.member.selectedBadgeEmoji` — MVC 렌더(OSIV). `Board.member` 는 원래 LAZY 라 **게시판은 이 변경 전부터 OSIV 에 의존**(실험 3).
- `@MessageMapping`·`@Scheduled`·`@Async`·`@EventListener`·`TaskScheduler` 를 쓰는 클래스 6개(`BatchScheduler`·`SchedulerConfig`·`WebSocketConfig`·`WebSocketHttpSessionGuard`·`RoomPresenceService`·`RoomUnloadService`) 중 `getGenre()`/`getSelectedBadge*`/`getSong()` 을 직접 호출하는 곳 **0**.
- 엔티티를 JSON 으로 직접 돌려주는 엔드포인트(`ResponseEntity<Song|Member|List<…>|Page<…>>`) **0** — LAZY 프록시 직렬화 문제 없음. 세션 주체 `CustomUserDetails` 는 id·email·role·status 만(엔티티 아님).
- 영향 받는 화면/기능: 관리자 곡 목록(`/admin/song`, `/content`)·관리자 정답 목록(`/admin/answer`)·랭킹 페이지/API(`/ranking`, `/api/ranking` 전 모드)·뱃지 표시가 있는 모든 회원 목록.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 재현(RED) | `./mvnw test -Dtest=NPlusOneQueryCountTest` — 수정 **전** | 곡 목록 4경로(`searchWithFilters`·`…MultipleArtists`·`findAll(pageable)`·`findByTitleContainingOrArtistContaining`) 모두 **7**(기대 ≤2) ❌ · 랭킹 **5**(기대 1) ❌ · LEFT JOIN 가드 2건 ✅(기존 동작) | ✅(의도된 실패) |
| 수정(GREEN) | 같은 명령 — 수정 **후** | 7건 전부 통과: 곡 목록 **2**, 랭킹 **1** | ✅ |
| 렌더링 가드 | `./mvnw test -Dtest=LazyAssociationRenderingTest` | 3건 통과(관리자 곡/정답 목록 장르명 포함 200 · `/api/ranking` 1위 badgeEmoji 🧪·badgeName) | ✅ |
| 실험 3 (OSIV 끔) | 일회성 `OsivOffExperimentTest`(`spring.jpa.open-in-view=false`, MockMvc, @Transactional 없음, **커밋 안 함**) | `/admin/song/content` 200 장르명 ✅ · `/admin/answer/content` 200 ✅ · `/api/ranking` 200 뱃지 ✅ · `/ranking` 200(서버 렌더 뱃지 없음, JS 가 API 호출) · **`/board`·`/board/{id}` → `LazyInitializationException: Could not initialize proxy [Member#2] - no session`**(`Board.member`, 이번 변경과 무관한 기존 LAZY) | ✅(관찰) |
| dev 기동 | `.claude/launch.json` `quiz-dev`(`spring-boot:run -Dspring-boot.run.profiles=dev`, 로컬 MySQL 8.0.46) | `validate` 통과, 헬스 UP, 기동 로그 **`WARN … spring.jpa.open-in-view is enabled by default`** 확인 · `/ranking` 200 · `/api/ranking` 5행, id 17 badgeEmoji 👑 badgeName 만점왕(LAZY 뱃지가 OSIV 안에서 로드) · 관리자 화면은 로컬 DB 관리자 비밀번호가 시드값과 달라 미확인 → MockMvc 가드로 대체 | ✅ |
| 전체 회귀 | `JAVA_HOME=jdk-17 ./mvnw test`(Docker 기동 중 — Redis 계약 테스트 포함) | **507 run · 0 failures · 0 errors · 0 skipped**(기존 497 + N+1 7 + 가드 3. Docker 기동 중이라 Redis 계약 테스트 포함) | ✅ |
| 운영 | push → Actions deploy → Smoke(§6) | — | 🙋 |

## 6. 수동 확인 시나리오 (운영 Smoke, 배포 후)
1. [전제] Actions `Build and Deploy` 성공, 활성 색 전환.
2. [행동] 브라우저 UA 로 `GET /ranking`·`GET /api/ranking?mode=guess&type=score` · 관리자 로그인 후 `/admin/song`(장르 열)·`/admin/answer`.
3. [기대 결과] 전부 200, 뱃지 이모지·장르명이 배포 전과 동일, 앱 로그에 `LazyInitializationException` 0건.
4. 실험 4: 서버 `grep -n log_format /etc/nginx/nginx.conf` 에 `$request_time` 이 있으면 `/admin/song`·`/ranking` 배포 전후 각 5건 비교, 없으면 "확인 안 함".
- 결과: ☐ 통과 ☐ 실패

## 7. checklist 점검
- 점검함: 1 영향 범위(호출처 전수 — Java·템플릿·배치·스케줄러·STOMP, §4) · 1 매핑 3자(엔티티 ↔ schema.sql — 컬럼 변경 없음, `validate` 통과) · 2 경계(결과 0건은 기존 `SongRepositoryTest`, null 장르·null 뱃지는 AC 4) · 4 트랜잭션(LAZY 를 트랜잭션 밖에서 건드리는 비요청 스레드 — §4 표, 실험 3) · 7 운영(H2 ↔ MariaDB: JPQL 만 바꿨고 native SQL 없음. `LEFT JOIN FETCH`+`countQuery` 는 Hibernate 가 생성 — DB 의존 없음)
- 해당 없음: 3 권한(경로 변경 없음) · 5 멀티플레이 · 6 UX(화면 변경 없음)

## 8. 발견된 문제와 조치
- (문제 아님, 관찰) 로컬 MySQL `EXPLAIN` 은 이번 범위 밖 — 인덱스 작업(10-10)과 달리 쿼리 **수**가 지표라 `Statistics` 로 셌다.
- 게시판이 OSIV 에 의존(`Board.member` LAZY, 실험 3) — 이번 변경 전부터 그렇고 OSIV 는 켜 둔다(결정 ④). 끄려면 `BoardService.getBoards` 류에 `JOIN FETCH b.member`(+ 뱃지) 가 먼저. 기록만.
- 죽은 랭킹 쿼리 3개(`findTopByTotalScore`·`findTopByAccuracy`·`findTopByTotalGames` + `MemberService.getTopRanking*`)는 10-10 기록 §6-4 와 같은 지적 — 손대지 않음.

## 9. 미검증 영역과 남은 위험
- 🙋 운영 Smoke(AC 8) — push 뒤.
- ⬜ 실험 4 운영 응답시간 — nginx 로그 포맷 확인 전. 46곡·46회원 규모라 수치는 서류에 쓰지 않는다(10-10 기록과 같은 원칙).
- ⬜ Part 2 `GameRound.song/genre`(O-034) — 호출처 62곳, 게임 결과·관리자 이력 화면.
- 랭킹 쿼리 33개를 일괄 치환했다 — 각 메서드의 ORDER BY·WHERE 는 손대지 않았고(`SELECT m FROM Member m WHERE` → `… LEFT JOIN FETCH m.selectedBadge WHERE` 만), 전체 회귀로 기존 랭킹 테스트(`RankingSnapshotBatchTest` 등)가 통과했다.

## 10. Regression 등록
- regression-list.md 추가 ID: R-052
