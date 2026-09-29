# 파티 퀴즈 P-2 Part ② — 본게임 규칙 (`PartyGameService`)
- 일자: 2026-09-30
- 유형: 신규
- 우선순위: P1
- 판정: 수용 가능 (서비스 계층까지. 화면·경로는 Part ④)

## 1. 요청과 목적
- 사용자가 원한 것: 본게임의 출제·판정·점수·힌트·재생 명령·보드 노출·이력·복구 규칙. plan §4·§8·§11-2 기준.
- 개발자 확인 결과(결정 사항, 2026-09-29 밤 "1~5 전부 추천대로"):
  1. 판정 정정은 **점수 ± 만**. 직전 판정 되돌리기 없음(이력의 득점 팀은 그대로 남는다).
  2. 노래는 **RETRO·비인기곡 포함**.
  3. 낼 수 없는 문제는 뽑지 않고 남은 수에도 세지 않는다. "미완성 N개" 표시는 Part ④ 목록 화면.
  4. 점수는 1점 고정(난이도 차등 없음).
  5. AC 는 초안 그대로.
- 진행 중 둔 가정:
  - **단계 READY 추가** — plan §11-2 의 단계는 WAIT/SHOW/REVEAL/SCORES/END 5개지만, MC 가 문제를 뽑고 정답 카드를 본 뒤 띄우기 전 상태가 필요하다. 내부 단계는 READY, 보드 상태에는 WAIT 로 나가고 문제는 실리지 않는다.
  - 라운드 번호는 [뽑기]가 아니라 [띄우기] 때 오른다. 띄우기 전에 다시 뽑으면 앞서 뽑은 문제는 다시 나올 수 있다(잘못 누른 대분류로 문제가 소모되지 않게).
  - 재생 명령은 문제가 떠 있을 때와 정답 공개 뒤에 된다(정답 뒤 끝까지 듣기). 뽑기만 한 상태에서는 거부 — TV 로 소리가 새지 않게.
  - 점수판·종료는 문제가 떠 있는 동안 거부("판정을 먼저 끝내세요"). 종료 뒤에는 [새 게임]만 된다.
  - 스냅샷 저장에 실패해도 진행은 막지 않고 ERROR 로그만 남긴다(행사 중 디스크 문제로 게임이 멈추는 쪽이 더 나쁘다). 재시작 복구는 안 된다.
  - `party.state-file` 이 비어 있으면 메모리에만 둔다. dev 만 `../party-images/party-state.json`.
  - 팀 이름은 남팀·여팀 고정(바꾸는 조작 없음).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 (`PartyGameServiceTest`) |
|---|---|---|---|---|
| 1 | 정상 | 고른 대분류·중분류 안에서 안 나온 문제 하나가 뽑힌다 | ✅ | `#picksWithinSubCategory` |
| 2 | 정상 | 중분류 "전체"는 대분류 전체에서 | ✅ | `#picksFromWholeCategory` |
| 3 | 정상 | 노래는 5년 묶음·전체, 연도 없는 곡은 전체에서만 | ✅ | `#picksSongByBand` |
| 4 | 경계 | 묶음 경계 1989·1990·1994·1995·2024·2025 | ✅ | `#songBandBoundaries` |
| 5 | 예외 | 같은 문제 두 번 안 나옴, 다 쓰면 거부 | ✅ | `#neverRepeats` |
| 6 | 예외 | 낼 수 없는 문제·곡은 뽑히지 않음 | ✅ | `#skipsUnplayable`, `#skipsUnplayableSongs` |
| 7 | 정상 | 대분류·중분류별 남은 수(낼 수 없는 것 제외) | ✅ | `#skipsUnplayable`, `#picksSongByBand` |
| 8 | 정상 | 팀 정답 = 1점 + 정답 공개 / 못 맞힘 = 점수 없이 정답 공개 | ✅ | `#correctAndMiss` |
| 9 | 정상 | 오답 표시는 보드만, 점수·이력 무관 | ✅ | `#wrongIsDisplayOnly` |
| 10 | 경계 | 점수 정정, 0 아래로 안 내려감 | ✅ | `#adjustsScore` |
| 11 | 예외 | 문제가 없을 때의 판정 거부, 두 번 눌러도 2점 안 됨 | ✅ | `#judgingOnlyWhileShowing` |
| 12 | 정상 | GAME 힌트 1 → 2 → 3, 그 뒤 거부 | ✅ | `#opensHintsInOrder` |
| 13 | 예외 | GAME 이 아니면 힌트 거부 | ✅ | `#hintsOnlyForGame` |
| 14 | 정상 | 재생 명령 순번 +1, IMAGE·TEXT 는 거부 | ✅ | `#playerCommands` |
| 15 | 노출 | 보드 상태에 정답 공개 전 정답·인정답안·보조·출처 없음 | ✅ | `#boardHidesAnswerUntilReveal` (JSON 문자열 검사) |
| 16 | 노출 | 안 연 힌트는 보드 상태에 없음 | ✅ | 같은 테스트 |
| 17 | 정상 | 라운드별 이력 | ✅ | `#recordsHistory` |
| 18 | 정상 | 재시작 뒤 점수·라운드·낸 문제·단계가 이어짐 | ✅ | `#restoresAfterRestart` (임시 폴더 파일) |
| 19 | 정상 | 새 게임은 전부 비우고 파일 삭제 | ✅ | `#newGameClearsEverything` |
| 20 | 예외 | 깨진 저장 파일이면 새 게임으로 기동 | ✅ | `#brokenSnapshotStartsFresh` |
| 21 | 연쇄 | 기존 게임 기록(GameSession) 불변 | ✅ | `#doesNotTouchGameSession` |
| 22 | 연쇄 | 기존 테스트 그대로 통과 | ✅ | 전체 519 = 481 + 12 + 26 |
| 23 | 정상 | 로컬 DB 의 실제 곡·문제로 뽑히고, dev 에서 스냅샷 파일이 생긴다 | ⬜ | 부르는 경로가 없다(Part ④) → O-028 |

추가로 확인한 것: 스피드퀴즈 제시어는 본게임에서 못 뽑음(`#speedIsNotPickable`), 띄우기 전 다시 뽑기(`#repickReleasesUnshownItem`), 문제가 떠 있는 동안 새 문제 거부(`#cannotPickWhileShowing`), 노래 정답 카드의 가수·연도·정답 변형(`#songCard`), 버전 증가(`#versionIncreases`), 점수판·종료(`#scoresAndEnd`).

## 3. 변경 사항
- `src/main/java/com/kh/game/party/PartyGameService.java` — 규칙
- `.../PartyGameState.java`, `PartyGameHolder.java` — 상태 1개 + JSON 스냅샷
- `.../PartyBoardView.java`, `PartyConsoleView.java` — 보드용·콘솔용 상태
- `.../PartyPhase.java`, `PartyTeam.java`, `PartyGameException.java`, `PartySongBand.java`
- `.../PartySongRepository.java` — 파티용 노래 조회(읽기만, 같은 `Song` 엔티티의 두 번째 리포지토리)
- `.../PartyItemRepository.java` — `findPlayable` 추가
- `src/main/resources/application-dev.properties` — `party.state-file=../party-images/party-state.json`
- `src/test/java/com/kh/game/party/PartyGameServiceTest.java` — 26건
- DB·설정 변경: DB 없음. 설정은 dev 속성 1줄(운영 속성은 건드리지 않음 — 운영에 올라가지 않는 브랜치).
- 기존 자바 파일 수정: 없음(`SongRepository`·`SongAnswerRepository` 는 그대로, 후자는 읽기 호출만).

## 4. 영향 범위 분석
- 호출처: 없음(컨트롤러는 Part ④).
- `Song` 을 읽는 새 리포지토리가 생겼지만 쓰기는 없다. 기존 게임 조회(`SongRepository`)는 변경 없음.
- 상태는 싱글턴 빈 하나. 서비스 메서드는 전부 `synchronized` — 콘솔은 하나뿐이고 보드는 읽기만 한다.
- 이미지 URL 은 `/admin/party/images/<파일명>` 로 만들어 보낸다. 그 경로의 핸들러는 Part ④.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Integration | `./mvnw test -Dtest='PartyGameServiceTest,PartyItemImportTest'` | 38 passed (26 + 12) | ✅ |
| 테스트가 규칙을 잡는지 | 코드 3곳을 일부러 망가뜨리고 재실행(정답 공개 조건 제거 · 단계 검사 제거 · 중복 제외 제거) 후 원복 | 4건 실패: `boardHidesAnswerUntilReveal` · `judgingOnlyWhileShowing` · `neverRepeats` · `restoresAfterRestart`. 원복 뒤 `diff` 일치 | ✅ |
| 전체 회귀 | `./mvnw test` (JDK 17, Docker 29.4.2) | Tests run: 519, Failures 0, Errors 0, Skipped 0, 4분 16초 | ✅ |
| 기동 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | 기동 12.5초, `/actuator/health` 200 UP | ✅ |
| 테스트가 저장소 밖에 파일을 만들지 않는지 | 전체 테스트·기동 뒤 `D:\dev\party-images` 확인 | 폴더 없음(테스트 프로필은 메모리, 기동만으로는 저장 안 함) | ✅ |
| 로컬 DB 실제 데이터 | — | 경로 없음 | ⬜ O-028 |

구현과 테스트를 같은 시점에 작성했다(실패 테스트 선행이 아님). 그래서 위 "일부러 망가뜨리기"로 테스트가 실제로 실패하는지 따로 확인했다.

## 6. 수동 확인 시나리오
- 없음(화면 없음). Part ④ 에서 작성.

## 7. checklist 점검
- 점검함: §1 새 속성은 dev 에만(운영 미배포 브랜치, 기본값은 빈 값 = 메모리) · §2 빈 중분류·null 연도·경계 연도·남은 0건 · §3 쿼리는 상수 JPQL(입력 연결 없음), 보드 상태에 정답 미노출 · §4 허용되지 않는 단계 전이 거부(SHOW 중 뽑기·점수판·종료, END 뒤 조작, REVEAL 밖의 다음), 같은 판정 중복 거부, 새로고침·재시작 뒤 상태 유지 · §7 복원·종료·저장 실패 로그.
- 해당 없음: 권한(경로 없음) · 멀티 · 화면 · DB 변경.
- 남은 것: 재시작 복구는 파일이 있을 때만. 저장 실패는 진행을 막지 않는다(가정, §1).

## 8. 발견된 문제와 조치
- 없음.

## 9. 미검증 영역과 남은 위험
- 로컬 DB 의 실제 곡(1857곡)으로 묶음별 남은 수·출제 / 경로 없음 / Part ④ 뒤 콘솔에서 확인(O-028).
- dev 에서 스냅샷 파일이 `../party-images/` 에 실제로 생기고 재시작 뒤 이어지는지 / 같은 이유 / O-028.
- `findPlayable` 의 JPQL enum 비교는 H2 에서만 실행됨. MySQL·MariaDB 에서도 같은 SQL 이지만 실행 확인은 O-028.
- 문제 수가 수백 개라 매 조작마다 전체를 읽어 자바에서 거른다. 곡 2000개 기준 id·연도만 읽으므로 문제없을 것으로 보지만 측정은 안 했다.

## 10. Regression 등록
- 없음(버그 수정 아님).
