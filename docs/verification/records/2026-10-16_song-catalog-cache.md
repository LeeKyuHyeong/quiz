# 게임 설정 화면 곡 집계·장르 목록 캐시 — Caffeine + 쓰기 경로 무효화 (career 【확인 46】)
- 일자: 2026-10-16
- 유형: 신규 (프로세스 안 캐시 — 스키마·설정 파일 변경 없음)
- 우선순위: P2 (게임 설정 화면 읽기 성능. 무효화가 틀리면 게임이 옛 곡 목록을 보므로 무효화 테스트는 P1 로 다룸)
- 판정: **조건부** — 코드·재현 테스트(적중/무효화/불변)·전체 회귀 ✅, dev 화면·운영 배포 🙋(O-036). **이 PR 은 외부 리뷰(현업 8년차)를 받기 위한 첫 PR** — 머지는 리뷰 반영 뒤

## 1. 요청과 목적
- 사용자가 원한 것: career `04-gap-analysis-java3y-261006.md` §4-4 【확인 46】 — 게임 설정 화면이 매 요청 읽는 장르 목록·곡 카탈로그 집계를 Caffeine 으로 캐시하고, 관리자 수정·배치 경로에서 무효화한다. 완료 기준은 **무효화 누락 테스트 1건**(관리자가 곡을 바꾼 뒤 게임이 옛 값을 보지 않음). ❌ Redis.
- 개발자 확인 결과(결정 사항, 2026-10-16): AC·설계 그대로 진행. 팬 챌린지 컨트롤러의 `removeIf` 수정 포함.
- 진행 중 둔 가정:
  - ① **캐시 대상은 "전체 활성 곡을 읽어 자바에서 집계"하는 세 메서드**(`getArtistsWithCount`·`getYearsWithCount`·`getArtistsWithCountForFanChallenge` — 모두 `findPopularSongsForGame`/`findByUseYnAndHasAudioSourceExcludingGenre` 로 전 곡 로드)와 게임용 장르 목록(`findActiveGenresForGame`). `getGenresWithSongCountMinimum` 은 이미 GROUP BY 쿼리라 제외, `getAvailableSongCount(GameSettings)` 는 키가 설정 객체라 이번 범위 밖(언급만).
  - ② 관리자용 `findActiveGenres()` 는 캐시하지 않는다 — 관리자 화면은 방금 바꾼 값을 바로 봐야 하고 호출 빈도가 낮다.
  - ③ 무효화는 `allEntries = true` 로 단순하게. 캐시 항목이 4개뿐이라 키별 무효화의 이득이 없다.
  - ④ `expireAfterWrite = 10분` 은 **무효화를 빠뜨린 경로가 생겼을 때의 안전망**이지 갱신 수단이 아니다. 정확성은 §3 의 @CacheEvict 가 책임진다.
  - ⑤ 단일 인스턴스 전제(운영은 blue-green 교대지만 동시에 한 색만 트래픽을 받음). 2대 이상이면 인스턴스마다 따로 비워야 하므로 그때는 외부 캐시 — `CacheConfig` 주석에 명시.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 아티스트·연도 집계와 게임용 장르 목록은 두 번째 호출부터 DB 를 읽지 않는다 | ✅ | `SongCatalogCacheTest$Hit` 2건 — Hibernate `Statistics.getQueryExecutionCount()` 로 두 번 호출에 쿼리 1회(장르 포함 2회). **수정 전 실패**(2회·4회) → 후 통과 (§5) |
| 2 | 정상 | 관리자가 곡을 비활성화·소프트 삭제·복구하면 다음 게임 요청이 바뀐 값을 본다 | ✅ | `$Invalidation.adminToggleUseYn_evictsArtists`(BTS 2→1) · `softDeleteAndRestore_evictsYears`(연도 곡 수 n→n-1→n) |
| 3 | 정상 | 관리자가 장르를 비활성화하면 게임용 장르 목록에서 빠진다 | ✅ | `$Invalidation.genreToggle_evictsGenres` |
| 4 | 연쇄 | 곡을 비활성화하는 배치가 돌아도 같은 조건으로 새 값을 본다 | ✅ | `$Invalidation.duplicateBatch_evictsArtists` — `DuplicateSongCheckBatch.execute(MANUAL)` 뒤 BTS 3→2. YouTube·파일 검사 배치는 같은 자리(`execute`)에 같은 어노테이션 — 외부 API·파일시스템이라 실행은 안 함 |
| 5 | 경계 | 캐시된 목록·항목을 호출자가 바꿀 수 없다 | ✅ | `$Immutable.cachedCollectionsAreUnmodifiable` — 목록 remove · 항목 put · 장르 목록 clear 전부 `UnsupportedOperationException`. **수정 전 실패** |
| 6 | 경계 | 팬 챌린지 아티스트 API 는 캐시 원본을 바꾸지 않고 걸러서 준다 | ✅ | `$Immutable.fanChallengeArtistsEndpoint_filtersCopy` — `/game/fan-challenge/artists` 200, 기준 미만 아티스트는 응답에 없고 캐시 원본에는 남음 |
| 7 | 예외 | 무효화를 빠뜨린 경로가 생겨도 10분 뒤 바로잡힌다 | ⬜ | TTL 은 Caffeine 설정값(`CacheConfig`)으로만 — 시간 경과 테스트는 쓰지 않음 |
| 8 | 회귀 | 전체 테스트 통과, 기존 테스트가 캐시 때문에 서로 간섭하지 않는다 | ✅ | **515 run · 0 failures · 0 errors · 11 skipped**(Redis 계약 — Docker 꺼짐, 기존과 동일). 간섭 방지는 `CacheClearingTestExecutionListener`(§3) |

## 3. 변경 사항
- `pom.xml` — `spring-boot-starter-cache` + `caffeine`(버전은 Boot BOM)
- `config/CacheConfig.java` — **신규**. `@EnableCaching`, `CaffeineCacheManager` 캐시 2개(`songCatalog`·`genres`, `expireAfterWrite 10m`·`maximumSize 100`). 이름을 고정해 두면 오타 난 이름의 `@Cacheable` 은 기동 시 실패
- `service/SongService.java` — 읽기 3개 `@Cacheable`(키 `'artists'`·`'years'`·`'fanChallengeArtists'`) + 반환을 `unmodifiableRows(...)`(불변 리스트·불변 맵 — 값에 null 이 있어 `Map.copyOf` 대신 `Collections.unmodifiableMap`). 쓰기 9개 `@CacheEvict(allEntries)`: `save`·`saveWithDuplicateCheck`·`deleteById`·`toggleUseYn`·`togglePopular`·`updatePopularity`·`softDeleteSong`·`restoreSong`·`mergeArtist`
- `service/GenreService.java` — `findActiveGenresForGame` `@Cacheable`(`List.copyOf`), 쓰기 3개(`save`·`deleteById`·`toggleUseYn`)가 `genres`·`songCatalog` 둘 다 비움(곡 집계 쿼리가 장르 코드로 거르므로)
- `service/BatchService.java` — 복구 3개(`restoreSong`·`restoreAllByHistory`·`restoreAllByBatchId`) `@CacheEvict`
- `batch/{YouTubeVideoCheck,SongFileCheck,DuplicateSongCheck}Batch.java` — `execute()` 에 `@CacheEvict`(곡 `useYn` 을 N 으로 바꾸는 배치 3종)
- `controller/client/GameFanChallengeController.java` — `artists.removeIf(...)` → stream `filter(...).toList()`. **캐시된 불변 목록을 제자리에서 지우던 코드** — 캐시 전에는 매 요청 새 리스트라 문제가 없었지만 캐시 뒤엔 다음 호출자가 지워진 목록을 받게 된다
- 테스트: `service/SongCatalogCacheTest.java` 8건(적중 2·무효화 4·불변 2, `@SpringBootTest` + `@Transactional` 없음 — 쓰기가 실제 커밋) · `support/CacheClearingTestExecutionListener.java` + `META-INF/spring.factories` — **테스트 메서드마다 캐시 전체 비움**(컨텍스트는 suite 전체 재사용·DB 는 메서드마다 롤백이라, 캐시만 남으면 앞 테스트의 곡 집계를 뒤 테스트가 본다. 캐시를 끄는 대신 비워서 전 suite 가 실제 캐시 위에서 돈다)
- DB·설정 변경: **없음**

## 4. 영향 범위 분석
- 캐시 읽기 호출처: `getArtistsWithCount`/`getYearsWithCount` ← `GameGuessController`·`GameHostController`·`MultiGameController`(설정 화면·`/artists`·`/years` API) · `getArtistsWithCountForFanChallenge` ← `GameFanChallengeController.getArtistsWithCount`·`FanChallengeService:539`(읽기만) · `findActiveGenresForGame` ← 게임 컨트롤러 4개 7곳(`GameGuess`·`GameHost`·`MultiGame`·장르 챌린지). **반환 목록을 변경하는 호출자는 `GameFanChallengeController` 1곳**(수정) — 나머지는 순회·자기 맵에 put
- 캐시 무효화가 필요한 쓰기 경로 전수(`Song.useYn`·`isPopular`·`artist`·`genre`·`releaseYear`·`youtubeVideoId`·`filePath` 를 바꾸거나 행을 넣고 지우는 곳):

| 경로 | 호출처 | 무효화 |
|---|---|---|
| `SongService` 쓰기 9개 | `AdminSongController`(save·delete·toggleUseYn·togglePopular), `AdminStatsController`(updatePopularity), `AdminArtist…`(mergeArtist), `MultiGameController:1176`(YouTube 무효 플래그 → `save`) | 메서드 `@CacheEvict` |
| `BatchService` 복구 3개 | `AdminBatchController`(영향 곡 복구) | 메서드 `@CacheEvict` |
| 배치 3종 `execute` | `BatchScheduler`·`AdminBatchController` 수동 실행 | 메서드 `@CacheEvict` |
| `GenreService` 쓰기 3개 | `AdminGenreController` | `genres`+`songCatalog` |
| `songRepository.save` 직접 호출 | `SongReportService`·`SongPopularityVoteService` — **`findById` 읽기만**, 쓰기 없음(10-16 확인) · `BatchService` 안의 호출은 위 복구 메서드 내부 | 해당 없음 |
| `SongAnswer` 추가·삭제(`addAnswer`·`updateAnswers`) | 집계에 영향 없음(곡 수·연도만) | 무효화 안 함 |

- 자기 호출(self-invocation): `saveWithDuplicateCheck` → `save` 는 프록시를 안 타지만 둘 다 어노테이션이 있어 무관. `getArtistsWithCountExcluding`·`…Filtered` 는 캐시 메서드를 부르지 않고 `findSongsForGame()` 을 직접 읽음 — 캐시 대상 아님(매 요청 전 곡 로드 그대로, 범위 밖 언급)
- 트랜잭션과 무효화 순서: `@CacheEvict`(기본 `beforeInvocation=false`)는 메서드 반환 직후·**커밋 전**에 비울 수 있다. 그 사이 다른 요청이 옛 값을 다시 채우면 커밋 뒤에도 최대 TTL 까지 옛 값이 남는 창이 이론상 있다. 관리자 1명·쓰기 드문 카탈로그라 감수 — 커밋 뒤 비우려면 `TransactionSynchronization.afterCommit` 으로 바꿔야 한다(PR 리뷰 포인트로 명시)
- 영향 받는 화면: 솔로 맞추기·문제내기·멀티 방 설정의 아티스트/연도 목록, 팬 챌린지 아티스트 목록, 게임 장르 선택. 관리자 화면은 변화 없음

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 재현(RED) | `./mvnw test -Dtest=SongCatalogCacheTest` — 구현 **전** | 적중 2건 ❌(쿼리 2회·4회 — 캐시 없음) · 불변 1건 ❌ · 무효화 4건은 캐시가 없으니 통과(가드) · 배치 테스트는 정리 단계 FK(`batch_affected_song`) 에러 → 정리 순서 수정 | ✅(의도된 실패) |
| 구현(GREEN) | 같은 명령 — 구현 **후** | **8 run · 0 failures**(Hit 2 · Invalidation 4 · Immutable 2) | ✅ |
| 전체 회귀 | `JAVA_HOME=jdk-17 ./mvnw test`(Docker 꺼짐) | **515 run · 0 failures · 0 errors · 11 skipped**(Redis 계약, 기존과 동일) = 507 + 8 | ✅ |
| 캐시 간섭 | 전체 회귀에서 `SongServiceTest`·`FanChallengeServiceTest` 등 곡 집계를 쓰는 기존 테스트 통과 — 리스너가 메서드마다 비움 | ✅ |
| dev 화면 | 솔로/호스트/멀티 설정 화면 · 팬 챌린지 아티스트 목록 · 관리자 곡 비활성화 뒤 설정 화면 재진입 | 미실행 | 🙋 O-036 |
| 운영 | 배포·Smoke | 머지 뒤 | 🙋 O-036 |

## 6. 수동 확인 시나리오 (dev 8082)
1. [전제] dev 기동(`spring-boot:run -Dspring-boot.run.profiles=dev`), 관리자 로그인.
2. [행동] 솔로 맞추기 설정 화면 → 아티스트 목록 확인 → 관리자에서 그 아티스트 곡 1개 비활성화 → 설정 화면 새로고침.
3. [기대 결과] 곡 수가 1 줄어 있다(또는 아티스트가 사라짐). 팬 챌린지 `/game/fan-challenge/artists` 응답이 200 이고 20곡 미만 아티스트가 없다. 기동 로그에 캐시 관련 오류 없음.
4. [행동] 관리자 장르 비활성화 → 게임 장르 선택 화면.
5. [기대 결과] 해당 장르가 목록에서 빠진다.
- 결과: ☐ 미실행

## 7. checklist 점검
- 점검함: 1 영향 범위(캐시 읽기 호출처·쓰기 경로 전수 — §4 표, 배치·스케줄러·MCP 포함) · 2 경계(빈 결과·null 값 — `unmodifiableMap` 선택 이유) · 4 트랜잭션(evict 와 커밋 순서 — §4 한계) · 4 캐시(변경 후 갱신·무효화 — AC 2~4) · 7 운영(단일 인스턴스 전제 — §1 ⑤)
- 해당 없음: 3 권한(경로 변경 없음) · 5 멀티플레이(상태 머신 변경 없음) · 6 UX(화면 변경 없음)

## 8. 발견된 문제와 조치
- `GameFanChallengeController` 가 서비스 반환 목록을 `removeIf` 로 제자리 수정 — 캐시 도입 시 다음 호출자가 깨진 목록을 받는 결함이 될 자리. 걸러서 새 목록으로 교체(AC 6).
- 테스트 시드의 `youtube_video_id` 가 컬럼 길이(20)를 넘어 첫 실행 전부 에러 → 마커를 8자리로 축소.
- (언급만) `getArtistsWithCountExcluding`·`getArtistsWithCountFiltered`·`getAvailableSongCount` 등 나머지 "전 곡 로드 후 자바 집계" 메서드는 매 요청 그대로 — 키가 인자(제외 곡 목록·설정)라 이번 캐시로 못 덮음. 다음 단계가 있다면 저장소 쿼리를 GROUP BY 로 바꾸는 쪽이 캐시보다 맞다.

## 9. 미검증 영역과 남은 위험
- 🙋 O-036 — dev 화면 확인(§6)과 머지 뒤 운영 배포·Smoke.
- ⬜ AC 7(TTL) — 시간 경과 테스트 없음. 설정값으로만.
- 위험: evict-커밋 순서 창(§4). 리뷰에서 `afterCommit` 전환 의견이 오면 그때 반영.
- 이 변경으로 **응답시간이 얼마나 줄었는지는 측정하지 않았다** — 곡 46·회원 46 규모라 의미 있는 수치가 안 나온다(10-13 N+1 기록과 같은 원칙). 서류엔 "무엇을·언제 무효화" 까지만.

## 10. Regression 등록
- 버그 수정이 아니라 추가 없음. 무효화 누락은 `SongCatalogCacheTest$Invalidation` 4건이 가드.
