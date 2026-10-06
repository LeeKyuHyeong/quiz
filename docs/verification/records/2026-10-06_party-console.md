# 파티 퀴즈 P-3 ① — MC 콘솔 화면 (`/admin/party/console`) + 자유 도전 표시(`freeChallenge`)
- 일자: 2026-10-06 (집 PC, 밤)
- 유형: 신규(화면) + 수정(서버 상태 1필드)
- 우선순위: P1 (행사 진행 핵심 동선이지만 운영 서비스 밖 — `party` 브랜치 · 로컬 전용)
- 판정: **조건부** — P1 AC 는 ✅, GAME 힌트·사진 문제·로그아웃 표시·플레이어 창 모드는 콘텐츠·화면이 아직 없어 🙋/⬜(아래 9)

## 1. 요청과 목적
- 사용자가 원한 것: P-3 화면 개발을 콘솔부터 시작.
- 개발자 확인 결과(결정 사항, 10-06 밤 채팅):
  - 설계는 plan §4 · 목업 `#console` · `party-status.md` "서버 계약"을 그대로 따른다(별도 spec 파일 없음).
  - **서버가 마지막 오답 팀 하나만 기억해서 "양 팀 오답 → 자유 도전"을 화면이 구분할 수 없는 문제** → 권장안 채택: 이번 문제의 오답 팀 집합을 두고 상태에 `freeChallenge` 를 싣는다.
- 진행 중 둔 가정:
  - 재생 명령은 **처음 받은 상태의 것은 실행하지 않는다**(콘솔 새로고침 때 노래가 다시 나오지 않게). 새로고침 뒤에는 MC 가 [재생]을 다시 누른다.
  - 클립 `길이`(duration)는 표시만 하고 자동 정지는 하지 않는다(MC 가 [일시정지]).
  - 대분류 판정 규칙 한 줄은 `docs/party-content/README.md` 표를 JS 상수로 옮겼다(규칙이 바뀌면 두 곳).
  - 대분류·중분류 선택 패널은 WAIT·READY·SCORES 에서만 보인다(REVEAL 에서는 [다음 라운드] 먼저).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 대분류 → 중분류 → [랜덤 뽑기] 하면 정답 카드(정답·인정답안·보조·출처·판정 규칙)가 보이고, [보드에 띄우기] 전에 영상·사진을 미리 확인할 수 있다 | ✅ | 브라우저: QUIZ·SONG 뽑기, SONG 미리 듣기 재생(state 1, 3초 진행) |
| 2 | 정상 | 재생형: 재생/일시정지/처음부터 → 팀 정답·오답 → 정답 공개 → 다음 라운드 | ✅ | 브라우저: PLAY 0→4초 재생 · PAUSE 4초 정지 · RESTART 0초부터 재생 · [못 맞힘] 뒤 공개 중 재생 유지 · [다음] 에 정지 |
| 3 | 정상 | 정지형: 띄우기 → 오답 → 상대 기회 → 양 팀 오답이면 **자유 도전** 표시 → 정답 +1 → 이력 → 다음 라운드 | ✅ | 브라우저 + `PartyGameServiceTest#bothWrongMeansFreeChallenge` |
| 4 | 정상 | GAME 힌트를 1→2→3 열기 | 🙋 | 서버는 기존 테스트로 ✅. 화면은 GAME 문제에 URL 이 아직 없어(출제 0) 못 봄 — 아래 6-2 |
| 5 | 정상 | 점수 ±, 점수판·대기 화면, [새 게임·이력 유지]/[전부 초기화], 종료 → 대기 복귀, [거두기] | ✅ | 브라우저(확인창 문구 3종 포함), 거두기 뒤 라운드 3→2·이력 미기록 |
| 6 | 예외 | 남은 문제가 0인 대분류·중분류는 고를 수 없고, 서버가 거부하면 이유가 보인다 | ✅ | 브라우저: SCREEN·ANIME·GAME·PERSON·SOUND 0 → 비활성. 거부 메시지는 공지 줄(`pcNotice`) |
| 7 | 경계 | 같은 버튼 연타는 한 번만 적용, 다른 창이 먼저 바꿨으면(낡은 버전) 거부되고 최신 상태로 다시 그린다 | ✅ | 브라우저: [+] 연타 → 1점만. 낡은 version POST → `{stale:true}` |
| 8 | 경계 | 앱 재시작 뒤에도 자유 도전 상태가 이어진다 | ✅ | `PartyGameServiceTest#restoresFreeChallengeAfterRestart` |
| 9 | 권한 | 비로그인은 로그인 화면, 일반 회원은 403 | ✅ | `PartyPageControllerTest` 3건 |
| 10 | 권한 | 세션이 끊기면 "로그아웃됨" 표시 | 🙋 | 코드 경로만(JSON 아님·리다이렉트 → 배너). 실제 만료는 아래 6-3 |
| 11 | 노출 | 정답은 콘솔에만, 보드 API 엔 공개 전까지 없음 | ✅ | 기존 `PartyGameServiceTest`(서버 보장). 콘솔은 MC 전용 경로 |

## 3. 변경 사항
- `party/PartyGameState.java` — `wrongTeams`(이번 문제의 오답 팀 집합) 추가. 기존 스냅샷엔 없으므로 빈 집합으로 읽힌다(`ignoreUnknown` + 기본값).
- `party/PartyGameService.java` — `wrong()` 이 집합에 추가, `clearQuestion()` 이 비움, `boardView()` 가 `freeChallenge`(두 팀 모두 오답, 띄운 동안만) 를 싣는다.
- `party/PartyBoardView.java` — `freeChallenge` 필드(보드·콘솔·플레이어 창 상태 공통).
- `party/PartyPageController.java` **신규** — `GET /admin/party/console` → `admin/party/console` (사이드바 `menu=party`).
- `templates/admin/party/console.html` **신규** — 목업 `#console` 구성(헤더 점수 · 선택 · 정답 카드 · 제시 · 판정 · 공개 · 종료 / 보드 상태 · 이력 · 설정).
- `static/js/admin/party-console.js` **신규** — 1초 폴링, 버전 비교, 조작 POST(version 포함, 응답까지 잠금), 실패·stale 시 상태 재수신, 남은 수는 열 때·조작 응답·다른 창 변경 감지 때만, 재생 명령 seq 추종(이 창 모드), 미리 듣기, 로그아웃 배너. 문제 내용은 전부 `textContent`.
- `static/css/admin/party-console.css` **신규** — 변수만, 라이트·다크, 768/480. 본문 폭으로 2열 → 1열(flex-wrap — 관리자 사이드바가 폭을 먹어 화면 폭 기준만으론 본문 열이 눌렸다).
- `static/js/common/youtube-player.js` — `init` 옵션 `width`·`height`(없으면 기존과 같은 `0`).
- `templates/admin/layout/sidebar.html` — "파티 퀴즈" 1줄.
- 테스트: `PartyGameServiceTest` +2, `PartyPageControllerTest` **신규** 3.
- DB·설정 변경: **없음**(상태는 JSON 스냅샷 파일, 필드 추가는 하위 호환).

## 4. 영향 범위 분석
- `PartyBoardView` 생성자: `PartyGameService#boardView` 한 곳뿐(검색). JSON 에 키 1개 추가 — 기존 계약 테스트(`PartyControllerTest` `$.state.board.wrongTeam`) 영향 없음.
- `wrongTeam` 사용처 6곳(서비스 3, 테스트 3) 모두 그대로. 의미 변화 없음(마지막 오답 팀).
- `YouTubePlayerManager.init` 호출처: `game-guess-play`·`game-host-play`·`game-retro-play`·`multi-play`(2) — 모두 `width`·`height` 를 넘기지 않으므로 0×0 그대로. 각 모드 재생 회귀는 수동 확인 안 함(⬜, 아래 9).
- 사이드바: 모든 관리자 화면이 쓰는 조각에 링크 1줄 — `menu == 'party'` 일 때만 active.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Unit(red) | `./mvnw test -Dtest=PartyGameServiceTest` (구현 전) | `freeChallenge` 없음 컴파일 실패 | ✅ |
| Unit | `./mvnw test -Dtest='PartyPageControllerTest,PartyGameServiceTest,PartyControllerTest'` | 3 · 45 · 16 passed | ✅ |
| 사용자 시나리오 | 집 PC `dev,session-jdbc` 기동, 실제 로그인한 관리자 브라우저(앱 패널), 실제 MySQL 문제 347행 + 노래 1,841 | 아래 6-1 | ✅ |
| 화면 | 라이트·다크 × 1440 / 768 / 375 | 가로 넘침 0(375: scrollWidth 375), 768 이하 1열, 375 에서 판정 버튼 한 줄씩 | ✅ |
| 전체 회귀 | `./mvnw test` (리포트 폴더 비운 뒤) | **613 passed, 0 failed, 0 skipped**(Docker 29.4.2 — Redis 계약 테스트 실행) | ✅ |

참고: 10-06 merge 때 609(+11 Skipped)로 1건 많게 센 것은 지우지 않은 옛 `PartyDevCheck` 리포트가 섞인 것이다. 리포트 폴더를 비우고 세면 기준선 608(파티 592 + MCP 16), 이번 +5 = 613.

## 6. 수동 확인 시나리오
### 6-1. 실행함(브라우저, 버튼은 실제 화면 클릭과 화면 스크립트의 click 으로)
1. QUIZ → 초성·과자(12) → [랜덤 뽑기]: 정답 카드 "오예스"·TEXT·정지형·난이도 중, 남은 수 12→11, 보드 상태 "대기 화면(뽑은 문제는 아직 안 보임)". ✅
2. [보드에 띄우기] → [남팀 오답] "남팀 오답 → 여팀 기회" → [여팀 오답] "양 팀 모두 오답 → 자유 도전"(강조), 정답 버튼 열림 → [여팀 정답] 여팀 1, 이력 1행 → [다음 라운드] 대기·선택 패널 복귀. ✅
3. SONG 전체 → 뽑기: 플레이어 480×270 생성(cued), [미리 듣기] 재생 → [보드에 띄우기] 시 0초로 다시 걸림 → [재생]·[일시정지]·[처음부터] 서버 명령 추종 → [못 맞힘] 공개 중 계속 재생 → [다음] 정지. ✅
4. 낡은 version 으로 `/score` POST → `stale:true`, 점수 불변. [+] 연타 → 1점. [−] → 0. [점수판 보이기] → 점수판, [대기 화면] → 대기. ✅
5. QUIZ 뽑기·띄우기 → [거두기](확인창) → 라운드 원복·이력 없음 → [게임 종료](확인창) → 최종 점수 → [대기로 복귀] → [전부 초기화](확인창) → 라운드 0·QUIZ 50 복귀. ✅
6. 끝난 뒤 상태 파일·`.bak-*` 를 지워 처음 상태로 둠(`D:\dev\party-images` 비어 있음).

### 6-2. 🙋 콘텐츠가 들어온 뒤 (GAME URL·사진 수집 후)
1. [전제] GAME 문제에 YouTube URL·힌트, PERSON/SCREEN 사진 파일이 `party.image-dir` 에 있음.
2. [행동] GAME → 뽑기 → 띄우기 → [다음 힌트 열기] 3회. PERSON → 뽑기 → 사진 미리보기 → 띄우기.
3. [기대] 힌트가 1→2→3 순서로 "보드에 보임"으로 바뀌고 4번째는 비활성. 사진이 미리보기에 보이고, 파일이 깨지면 빨간 경고.

### 6-3. 🙋 로그아웃 표시
1. 콘솔을 연 채로 다른 기기에서 같은 계정으로 로그인(1계정 1세션) 또는 앱을 `dev` 프로필(메모리 세션)로 재시작.
2. [기대] 콘솔 상단에 "로그아웃됨 … 다시 로그인" 배너, 버튼 전부 잠김(또는 기존 세션 감시가 로그인 화면으로 보냄).

## 7. checklist 점검
- 점검함: 1 영향 범위(호출처 검색 — 4번), 1 화면 파라미터명(서버 `@RequestParam` 과 JS 키 일치: version·category·subCategory·team·delta·keepUsed), 3 권한(비로그인·일반 회원 테스트), 3 XSS(문제 내용 전부 `textContent`), 4 중복 조작(연타·stale), 4 상태 전이(서버가 막음 — 화면은 단계별 버튼 비활성), 4 새로고침(재생 명령 재실행 안 함 — 가정), 6 실패 표시(공지 줄)·성공 반영·처리 중 잠금.
- 해당 없음: DB 변경, 트랜잭션, 멀티플레이, 요청 제한(관리자 전용·로컬), 타임존.

## 8. 발견된 문제와 조치
- 문제: 두 팀이 모두 틀려도 상태엔 마지막 팀만 남아 화면이 "○팀 기회"로 잘못 안내(10-06 자유 도전 규칙 미반영). / 원인: `wrongTeam` 단일 값. / 조치: `wrongTeams` 집합 + `freeChallenge`. / 테스트: `bothWrongMeansFreeChallenge`, `restoresFreeChallengeAfterRestart`.
- 문제: 관리자 사이드바 때문에 화면 폭 800 에서 본문 열이 ~200px 로 눌림. / 조치: 2열을 화면 폭이 아니라 본문 폭 기준으로 접음(flex-wrap). / 확인: 1440·800·768·375 스크린샷.

## 9. 미검증 영역과 남은 위험
- GAME 힌트·사진 문제 화면(🙋 6-2) — 콘텐츠 수집 뒤.
- 로그아웃 배너(🙋 6-3).
- "재생 담당: 플레이어 창" 모드 — 플레이어 창 화면이 P-3 다음 단계라 이 창이 명령을 안 따르는 것만 구현(⬜).
- 기존 모드(Solo Guess·Host·Retro·Multi) 재생 회귀를 브라우저로 안 봄(⬜) — `youtube-player.js` 기본값이 그대로라 코드상 영향 없음.
- 휴대폰 실기기(콘솔 예비 구성)로는 안 봄 — 375 에뮬레이션만(🙋, 리허설 1).

## 10. Regression 등록
- R-052 (자유 도전 표시)
